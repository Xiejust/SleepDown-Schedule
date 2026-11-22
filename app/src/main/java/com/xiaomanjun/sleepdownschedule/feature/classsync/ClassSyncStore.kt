package com.xiaomanjun.sleepdownschedule.feature.classsync

import android.content.Context
import android.content.SharedPreferences

internal class ClassSyncStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("class_sync_settings", Context.MODE_PRIVATE)

    fun load(): ClassSyncSettings = ClassSyncSettings(
        serverUrl = prefs.getString(KeyServerUrl, "").orEmpty().ifBlank { DefaultServerUrl },
        classCode = prefs.getString(KeyClassCode, "").orEmpty(),
        className = prefs.getString(KeyClassName, "").orEmpty(),
        teacher = prefs.getString(KeyTeacher, "").orEmpty(),
        role = prefs.getString(KeyRole, "").orEmpty(),
        adminToken = prefs.getString(KeyAdminToken, "").orEmpty(),
        memberId = prefs.getString(KeyMemberId, "").orEmpty(),
        nickname = prefs.getString(KeyNickname, "").orEmpty(),
        linkedScheduleId = prefs.getInt(KeyLinkedScheduleId, -1),
        lastVersion = prefs.getInt(KeyLastVersion, 0),
        lastSyncedAt = prefs.getLong(KeyLastSyncedAt, 0L),
        memberCount = prefs.getInt(KeyMemberCount, 0),
        autoPublish = prefs.getBoolean(KeyAutoPublish, false),
        lastPublishedHash = prefs.getString(KeyLastPublishedHash, "").orEmpty(),
    )

    fun save(settings: ClassSyncSettings) {
        prefs.edit()
            .putString(KeyServerUrl, settings.serverUrl)
            .putString(KeyClassCode, settings.classCode)
            .putString(KeyClassName, settings.className)
            .putString(KeyTeacher, settings.teacher)
            .putString(KeyRole, settings.role)
            .putString(KeyAdminToken, settings.adminToken)
            .putString(KeyMemberId, settings.memberId)
            .putString(KeyNickname, settings.nickname)
            .putInt(KeyLinkedScheduleId, settings.linkedScheduleId)
            .putInt(KeyLastVersion, settings.lastVersion)
            .putLong(KeyLastSyncedAt, settings.lastSyncedAt)
            .putInt(KeyMemberCount, settings.memberCount)
            .putBoolean(KeyAutoPublish, settings.autoPublish)
            .putString(KeyLastPublishedHash, settings.lastPublishedHash)
            .apply()
    }

    fun saveServerUrl(url: String) {
        prefs.edit().putString(KeyServerUrl, url).apply()
    }

    fun clearBinding() {
        prefs.edit()
            .remove(KeyClassCode)
            .remove(KeyClassName)
            .remove(KeyTeacher)
            .remove(KeyRole)
            .remove(KeyAdminToken)
            .remove(KeyMemberId)
            .remove(KeyNickname)
            .remove(KeyLinkedScheduleId)
            .putInt(KeyLastVersion, 0)
            .putLong(KeyLastSyncedAt, 0L)
            .putInt(KeyMemberCount, 0)
            .putBoolean(KeyAutoPublish, false)
            .remove(KeyLastPublishedHash)
            .apply()
    }

    private companion object {
        /** 默认同步服务器，可在此处改成自己的部署地址。 */
        const val DefaultServerUrl = "https://sleepdown-class-sync-pages.pages.dev"

        const val KeyServerUrl = "server_url"
        const val KeyClassCode = "class_code"
        const val KeyClassName = "class_name"
        const val KeyTeacher = "class_teacher"
        const val KeyRole = "role"
        const val KeyAdminToken = "admin_token"
        const val KeyMemberId = "member_id"
        const val KeyNickname = "nickname"
        const val KeyLinkedScheduleId = "linked_schedule_id"
        const val KeyLastVersion = "last_version"
        const val KeyLastSyncedAt = "last_synced_at"
        const val KeyMemberCount = "member_count"
        const val KeyAutoPublish = "auto_publish"
        const val KeyLastPublishedHash = "last_published_hash"
    }
}