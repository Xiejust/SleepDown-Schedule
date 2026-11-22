package com.xiaomanjun.sleepdownschedule.feature.classsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.CourseScheduleTheme
import com.xiaomanjun.sleepdownschedule.feature.schedule.ScheduleScreenViewModel
import com.xiaomanjun.sleepdownschedule.model.AppState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class ClassSyncActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as CourseScheduleApp
            val viewModel: ScheduleScreenViewModel = viewModel(
                factory = ScheduleScreenViewModel.Factory(app, app.repository, app.applicationScope)
            )
            val state by viewModel.allSchedulesState.collectAsStateWithLifecycle()
            CourseScheduleTheme(config = state.config) {
                ClassSyncScreen(app, state, onClose = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassSyncScreen(
    app: CourseScheduleApp,
    state: AppState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(app) { ClassSyncManager(context, app.repository) }
    var settings by remember { mutableStateOf(manager.loadSettings()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var overview by remember { mutableStateOf<AdminOverview?>(null) }
    var selectedScheduleId by remember {
        mutableStateOf(settings.linkedScheduleId.takeIf { it > 0 } ?: state.config.id)
    }
    var mode by rememberSaveable { mutableStateOf("join") }
    var serverUrl by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var classNameInput by rememberSaveable { mutableStateOf("") }
    var teacherInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var nicknameInput by rememberSaveable { mutableStateOf("") }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDeleteClass by remember { mutableStateOf(false) }

    LaunchedEffect(settings.linkedScheduleId, state.schedules) {
        if (state.schedules.none { it.id == selectedScheduleId }) {
            selectedScheduleId = state.schedules.firstOrNull()?.id ?: state.config.id
        }
    }
    LaunchedEffect(settings.isJoined, settings.isAdmin) {
        if (settings.isJoined && settings.isAdmin) {
            runCatching { manager.fetchAdminOverview() }.onSuccess { overview = it }
        }
    }

    fun refreshSettings() {
        settings = manager.loadSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("班级课表") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            message?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            if (!settings.isJoined) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("同步服务器地址") },
                    placeholder = { Text("https://sleepdown-class-sync-pages.pages.dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { mode = "join" },
                        enabled = mode != "join",
                        modifier = Modifier.weight(1f),
                    ) { Text("加入班级") }
                    OutlinedButton(
                        onClick = { mode = "create" },
                        enabled = mode != "create",
                        modifier = Modifier.weight(1f),
                    ) { Text("我是管理员") }
                }

                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (mode == "create") {
                            Text("创建班级", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "创建后会得到班级号和管理员令牌。管理员令牌只保存在本机，请勿清理应用数据前忘记已发布的课表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = classNameInput,
                                onValueChange = { classNameInput = it },
                                label = { Text("班级名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = teacherInput,
                                onValueChange = { teacherInput = it },
                                label = { Text("教师 / 管理员（可选）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = nicknameInput,
                                onValueChange = { nicknameInput = it },
                                label = { Text("我的昵称（可选）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("加入口令（可选，至少 4 位）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    if (classNameInput.isBlank()) {
                                        message = "请填写班级名称"
                                        return@Button
                                    }
                                    busy = true
                                    message = null
                                    scope.launch {
                                        runCatching {
                                            manager.saveServerUrl(serverUrl)
                                            manager.createClass(
                                                serverUrl = serverUrl,
                                                name = classNameInput,
                                                teacher = teacherInput,
                                                joinPassword = passwordInput,
                                                nickname = nicknameInput.ifBlank { "管理员" },
                                            )
                                        }.onSuccess {
                                            refreshSettings()
                                            message = "班级已创建：${ClassSyncManager.displayClassCode(it.classCode)}"
                                        }.onFailure {
                                            message = it.message ?: "创建班级失败"
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("创建班级") }
                        } else {
                            Text("加入班级", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "向管理员索取 8 位班级号；加入后会自动收到管理员发布的课表，并在每次打开应用时检查更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it },
                                label = { Text("班级号") },
                                placeholder = { Text("ABCD-EFGH") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = nicknameInput,
                                onValueChange = { nicknameInput = it },
                                label = { Text("我的昵称（可选）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("加入口令（如管理员设置了）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    val code = ClassSyncManager.normalizeClassCode(codeInput)
                                    if (code.length != 8) {
                                        message = "班级号应为 8 位字母或数字"
                                        return@Button
                                    }
                                    busy = true
                                    message = null
                                    scope.launch {
                                        runCatching {
                                            manager.saveServerUrl(serverUrl)
                                            manager.joinClass(
                                                serverUrl = serverUrl,
                                                code = code,
                                                password = passwordInput,
                                                nickname = nicknameInput,
                                            )
                                        }.onSuccess {
                                            refreshSettings()
                                            message = if (it.lastVersion > 0) {
                                                "已加入班级并同步课表（版本 ${it.lastVersion}）"
                                            } else {
                                                "已加入班级，等待管理员发布课表"
                                            }
                                        }.onFailure {
                                            message = it.message ?: "加入班级失败"
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加入班级") }
                        }
                    }
                }
            } else {
                ClassInfoCard(settings)

                if (settings.isAdmin) {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("发布课表", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "选择要同步给同学的课表。发布后，同学下次打开应用即可收到；修改课表后再次发布即可更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.schedules.forEach { schedule ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    RadioButton(
                                        selected = selectedScheduleId == schedule.id,
                                        onClick = { selectedScheduleId = schedule.id },
                                    )
                                    Column(modifier = Modifier.padding(start = 4.dp)) {
                                        Text(schedule.name)
                                        val courseCount = state.allCourses.count { it.scheduleId == schedule.id }
                                        Text(
                                            "$courseCount 门课程",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("自动发布")
                                    Text(
                                        "退到后台或应用启动时若检测到课表有改动，自动上传",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = settings.autoPublish,
                                    onCheckedChange = { checked ->
                                        busy = true
                                        scope.launch {
                                            runCatching { manager.setAutoPublish(checked) }
                                                .onFailure { message = it.message ?: "设置失败" }
                                            refreshSettings()
                                            busy = false
                                        }
                                    }
                                )
                            }
                            Button(
                                onClick = {
                                    busy = true
                                    message = null
                                    scope.launch {
                                        runCatching { manager.publish(selectedScheduleId) }
                                            .onSuccess { outcome ->
                                                refreshSettings()
                                                val version = (outcome as? PublishOutcome.Published)?.version
                                                message = if (version != null) {
                                                    "已发布课表版本 $version，同学同步后即可看到"
                                                } else {
                                                    "课表已发布，同学同步后即可看到"
                                                }
                                            }
                                            .onFailure { message = it.message ?: "发布失败" }
                                        busy = false
                                    }
                                },
                                enabled = !busy && state.schedules.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("发布到班级") }
                        }
                    }

                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "班级成员（${overview?.memberCount ?: settings.memberCount}）",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            runCatching { manager.fetchAdminOverview() }
                                                .onSuccess { overview = it }
                                                .onFailure { message = it.message ?: "获取成员失败" }
                                            busy = false
                                        }
                                    }
                                ) { Text("刷新") }
                            }
                            overview?.members?.forEach { member ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(if (member.role == "admin") "${member.nickname}（管理员）" else member.nickname)
                                    Text(
                                        formatTime(member.lastSeenAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (overview?.members.isNullOrEmpty()) {
                                Text(
                                    "暂无成员，点击刷新获取最新列表",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("同步课表", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "管理员更新课表后，打开应用或点击下方按钮即可同步。同步会覆盖「${settings.className}」这个本地课表，不影响其他课表。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    busy = true
                                    message = null
                                    scope.launch {
                                        runCatching { manager.syncNow() }
                                            .onSuccess { outcome ->
                                                message = when (outcome) {
                                                    SyncOutcome.NotJoined -> "尚未加入班级"
                                                    is SyncOutcome.UpToDate -> "已是最新版本（${outcome.settings.lastVersion}）"
                                                    is SyncOutcome.NoSchedule -> "管理员还没有发布课表"
                                                    is SyncOutcome.Updated -> "已同步到版本 ${outcome.version}"
                                                }
                                                refreshSettings()
                                            }
                                            .onFailure { message = it.message ?: "同步失败" }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("立即同步") }
                        }
                    }
                }

                if (settings.isAdmin) {
                    Card {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("班级管理", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { confirmDeleteClass = true }) {
                                Text("解散班级（删除云端数据）", color = MaterialTheme.colorScheme.error)
                            }
                            TextButton(onClick = { confirmLeave = true }) {
                                Text("仅在本机退出")
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { confirmLeave = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("退出班级") }
                }
            }

            Divider()
            Text(
                "本功能为基于 SleepDown 的非官方修改版功能；课表同步到你自己部署的服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("退出班级？") },
            text = { Text("只会解除本机绑定，云端的班级和课表不会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    busy = true
                    scope.launch {
                        manager.leave(deleteRemoteClass = false)
                        refreshSettings()
                        message = "已在本机退出班级"
                        busy = false
                    }
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } },
        )
    }
    if (confirmDeleteClass) {
        AlertDialog(
            onDismissRequest = { confirmDeleteClass = false },
            title = { Text("解散班级？") },
            text = { Text("将删除云端班级、成员记录和已发布课表，所有同学将无法继续同步。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteClass = false
                    busy = true
                    scope.launch {
                        runCatching { manager.leave(deleteRemoteClass = true) }
                            .onSuccess { message = "班级已解散" }
                            .onFailure { message = it.message ?: "解散失败" }
                        refreshSettings()
                        busy = false
                    }
                }) { Text("确认解散", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteClass = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ClassInfoCard(settings: ClassSyncSettings) {
    val context = LocalContext.current
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(settings.className, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (settings.teacher.isNotBlank()) {
                Text(settings.teacher, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "班级号：${ClassSyncManager.displayClassCode(settings.classCode)}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("班级号", ClassSyncManager.displayClassCode(settings.classCode))
                    )
                }) { Text("复制") }
            }
            Text(
                if (settings.isAdmin) "身份：管理员" else "身份：同学",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "课表版本：${settings.lastVersion} · 成员：${settings.memberCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "上次同步：${formatTime(settings.lastSyncedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(value: Long): String {
    if (value <= 0L) return "尚未同步"
    return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(timeFormatter)
}