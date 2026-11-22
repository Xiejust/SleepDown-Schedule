package com.xiaomanjun.sleepdownschedule.feature.classsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xiaomanjun.sleepdownschedule.data.repository.ScheduleRepository
import com.xiaomanjun.sleepdownschedule.feature.importing.CanonicalScheduleJson
import com.xiaomanjun.sleepdownschedule.feature.importing.buildCanonicalScheduleJson
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 班级课表同步的业务层。
 *
 * 发布端把当前课表编码为 SleepDown 规范化 JSON；接收端解码后写入一个独立的本地课表，
 * 后续更新只覆盖这个课表，不影响本机其他课表。
 */
internal class ClassSyncManager(
    context: Context,
    private val repository: ScheduleRepository,
) {
    private val appContext = context.applicationContext
    private val store = ClassSyncStore(appContext)

    fun loadSettings(): ClassSyncSettings = store.load()

    fun saveServerUrl(url: String) = store.saveServerUrl(url.trim())

    suspend fun createClass(
        serverUrl: String,
        name: String,
        teacher: String,
        joinPassword: String?,
        nickname: String,
    ): ClassSyncSettings = withContext(Dispatchers.IO) {
        val client = ClassSyncClient(serverUrl)
        val response = client.createClass(
            CreateClassRequest(
                name = name.trim(),
                teacher = teacher.trim().ifBlank { null },
                joinPassword = joinPassword?.takeIf { it.isNotBlank() },
                nickname = nickname.trim().ifBlank { null },
            )
        )
        val activeId = repository.activeSnapshot().config.id
        val settings = ClassSyncSettings(
            serverUrl = serverUrl.trim(),
            classCode = response.code,
            className = response.classInfo.name,
            teacher = response.classInfo.teacher,
            role = "admin",
            adminToken = response.adminToken,
            memberId = "",
            nickname = nickname.trim(),
            linkedScheduleId = activeId,
            lastVersion = 0,
            lastSyncedAt = 0,
            memberCount = response.classInfo.memberCount,
            autoPublish = false,
        )
        store.save(settings)
        ClassSyncWorker.schedule(appContext)
        settings
    }

    suspend fun joinClass(
        serverUrl: String,
        code: String,
        password: String?,
        nickname: String,
    ): ClassSyncSettings = withContext(Dispatchers.IO) {
        val normalizedCode = normalizeClassCode(code)
        require(normalizedCode.length == 8) { "班级号应为 8 位字母或数字" }
        val previous = store.load()
        val client = ClassSyncClient(serverUrl)
        val response = client.joinClass(
            normalizedCode,
            JoinClassRequest(
                password = password?.takeIf { it.isNotBlank() },
                nickname = nickname.trim().ifBlank { null },
                memberId = previous.memberId.takeIf { previous.classCode == normalizedCode },
            )
        )
        var settings = ClassSyncSettings(
            serverUrl = serverUrl.trim(),
            classCode = response.classInfo.code,
            className = response.classInfo.name,
            teacher = response.classInfo.teacher,
            role = "member",
            adminToken = "",
            memberId = response.memberId,
            nickname = nickname.trim(),
            linkedScheduleId = if (
                previous.classCode == response.classInfo.code &&
                previous.serverUrl.trim() == serverUrl.trim()
            ) previous.linkedScheduleId else -1,
            lastVersion = 0,
            lastSyncedAt = 0,
            memberCount = response.classInfo.memberCount,
        )
        val schedule = response.schedule
        if (schedule != null) {
            val applied = applySchedule(schedule, settings)
            settings = settings.copy(
                linkedScheduleId = applied,
                lastVersion = schedule.version,
                lastSyncedAt = System.currentTimeMillis(),
            )
        }
        store.save(settings)
        ClassSyncWorker.schedule(appContext)
        settings
    }

    /** 学生端：拉取并应用最新课表。 */
    suspend fun syncNow(): SyncOutcome = withContext(Dispatchers.IO) {
        val settings = store.load()
        if (!settings.isJoined) return@withContext SyncOutcome.NotJoined
        val client = ClassSyncClient(settings.serverUrl)
        val response = client.fetchSchedule(settings.classCode, settings.lastVersion, settings.memberId)
        if (response.notModified) {
            val updated = settings.copy(
                lastSyncedAt = System.currentTimeMillis(),
                memberCount = response.classInfo?.memberCount ?: settings.memberCount,
            )
            store.save(updated)
            return@withContext SyncOutcome.UpToDate(updated)
        }
        val schedule = response.schedule ?: return@withContext SyncOutcome.NoSchedule(
            settings.copy(
                lastSyncedAt = System.currentTimeMillis(),
                memberCount = response.classInfo?.memberCount ?: settings.memberCount,
            ).also(store::save)
        )
        val appliedId = applySchedule(schedule, settings)
        val updated = settings.copy(
            classCode = response.classInfo?.code ?: settings.classCode,
            className = response.classInfo?.name ?: settings.className,
            teacher = response.classInfo?.teacher ?: settings.teacher,
            linkedScheduleId = appliedId,
            lastVersion = schedule.version,
            lastSyncedAt = System.currentTimeMillis(),
            memberCount = response.classInfo?.memberCount ?: settings.memberCount,
        )
        store.save(updated)
        SyncOutcome.Updated(updated, schedule.version)
    }

    /** 管理端：把指定课表发布到班级。 */
    suspend fun publish(scheduleId: Int): PublishOutcome = withContext(Dispatchers.IO) {
        val settings = store.load()
        require(settings.isJoined) { "尚未创建或加入班级" }
        require(settings.isAdmin) { "只有管理员可以发布课表" }
        val payload = payloadFor(scheduleId)
        val client = ClassSyncClient(settings.serverUrl)
        val response = client.publishSchedule(settings.classCode, settings.adminToken, payload)
        val updated = settings.copy(
            linkedScheduleId = scheduleId,
            lastVersion = response.version,
            lastSyncedAt = System.currentTimeMillis(),
            lastPublishedHash = sha256(payload),
            className = response.classInfo?.name ?: settings.className,
            teacher = response.classInfo?.teacher ?: settings.teacher,
        )
        store.save(updated)
        PublishOutcome.Published(updated, response.version)
    }

    /** 应用启动时调用：学生拉取更新；管理员按需自动发布。 */
    suspend fun onAppStart() {
        val settings = store.load()
        if (!settings.isJoined) return
        runCatching {
            if (settings.isAdmin) {
                if (settings.autoPublish && settings.linkedScheduleId > 0) autoPublishIfNeeded()
            } else {
                syncNow()
            }
        }
    }

    /** 管理端自动发布：只有课表内容变化时才上传。 */
    suspend fun autoPublishIfNeeded(): PublishOutcome? = withContext(Dispatchers.IO) {
        val settings = store.load()
        if (!settings.isJoined || !settings.isAdmin || !settings.autoPublish) return@withContext null
        val scheduleId = settings.linkedScheduleId
        if (scheduleId <= 0) return@withContext null
        val exists = runCatching { repository.scheduleSnapshot(scheduleId) }.isSuccess
        if (!exists) return@withContext null
        val payload = payloadFor(scheduleId)
        if (sha256(payload) == settings.lastPublishedHash) return@withContext null
        publish(scheduleId)
    }

    suspend fun fetchAdminOverview(): AdminOverview = withContext(Dispatchers.IO) {
        val settings = store.load()
        require(settings.isAdmin) { "只有管理员可以查看成员" }
        val client = ClassSyncClient(settings.serverUrl)
        val response = client.adminOverview(settings.classCode, settings.adminToken)
        store.save(settings.copy(
            className = response.classInfo.name,
            teacher = response.classInfo.teacher,
            memberCount = response.classInfo.memberCount,
        ))
        AdminOverview(
            className = response.classInfo.name,
            memberCount = response.classInfo.memberCount,
            members = response.members,
            scheduleVersion = response.classInfo.scheduleVersion,
        )
    }

    suspend fun setAutoPublish(enabled: Boolean) {
        store.save(store.load().copy(autoPublish = enabled))
        if (enabled) runCatching { autoPublishIfNeeded() }
    }

    suspend fun leave(deleteRemoteClass: Boolean = false) = withContext(Dispatchers.IO) {
        val settings = store.load()
        if (deleteRemoteClass && settings.isAdmin) {
            val client = ClassSyncClient(settings.serverUrl)
            client.deleteClass(settings.classCode, settings.adminToken)
        }
        ClassSyncWorker.cancel(appContext)
        store.clearBinding()
    }

    fun schedulePeriodicSync() {
        ClassSyncWorker.schedule(appContext)
    }

    private suspend fun payloadFor(scheduleId: Int): String {
        val snapshot = repository.scheduleSnapshot(scheduleId)
        return buildCanonicalScheduleJson(
            snapshot.config,
            snapshot.periods,
            snapshot.courses,
            snapshot.timetableCatalog,
        )
    }

    /** 把云端课表写成本地课表；已绑定的课表原地覆盖，否则新建一个。 */
    private suspend fun applySchedule(snapshot: ScheduleSnapshotDto, settings: ClassSyncSettings): Int {
        val linkedId = settings.linkedScheduleId
        val profiles = repository.snapshot().schedules
        val linkedExists = linkedId > 0 && profiles.any { it.id == linkedId }
        val base = if (linkedExists) repository.scheduleSnapshot(linkedId) else repository.activeSnapshot()
        val draft = CanonicalScheduleJson.decode(snapshot.payload, base.config, base.periods)
        if (linkedExists) {
            return try {
                repository.importDraftForSchedule(linkedId, draft)
                linkedId
            } catch (error: Exception) {
                createAndNameSchedule(draft, settings)
            }
        }
        return createAndNameSchedule(draft, settings)
    }

    private suspend fun createAndNameSchedule(
        draft: com.xiaomanjun.sleepdownschedule.model.ImportDraft,
        settings: ClassSyncSettings,
    ): Int {
        val id = repository.importDraft(draft, createNewSchedule = true)
        repository.renameSchedule(id, "班级 · ${settings.className.ifBlank { "同步课表" }}")
        return id
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        fun normalizeClassCode(raw: String): String = raw.uppercase().replace(Regex("[\\s-]"), "")

        fun displayClassCode(code: String): String =
            if (code.length == 8) "${code.take(4)}-${code.drop(4)}" else code
    }
}

internal sealed interface SyncOutcome {
    data object NotJoined : SyncOutcome
    data class UpToDate(val settings: ClassSyncSettings) : SyncOutcome
    data class NoSchedule(val settings: ClassSyncSettings) : SyncOutcome
    data class Updated(val settings: ClassSyncSettings, val version: Int) : SyncOutcome
}

internal sealed interface PublishOutcome {
    data class Published(val settings: ClassSyncSettings, val version: Int) : PublishOutcome
}

internal data class AdminOverview(
    val className: String,
    val memberCount: Int,
    val members: List<ClassMemberDto>,
    val scheduleVersion: Int,
)