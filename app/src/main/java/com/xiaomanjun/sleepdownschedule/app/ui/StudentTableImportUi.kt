package com.xiaomanjun.sleepdownschedule.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.AppState
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import com.xiaomanjun.sleepdownschedule.CourseScheduleTheme
import com.xiaomanjun.sleepdownschedule.EduSchoolSelectActivity
import com.xiaomanjun.sleepdownschedule.ImportDraft
import com.xiaomanjun.sleepdownschedule.app.state.ScheduleViewModel
import com.xiaomanjun.sleepdownschedule.app.state.ScheduleViewModelFactory
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.DetailActivityScaffold
import com.xiaomanjun.sleepdownschedule.core.ui.scaffold.detailContentTopPadding
import com.xiaomanjun.sleepdownschedule.core.ui.settings.CouiSettingsTabletDetailPaneScaffold
import com.xiaomanjun.sleepdownschedule.feature.course.management.HomeMenuActivitySourceFallback
import com.xiaomanjun.sleepdownschedule.feature.importing.ConfirmScheduleScreen
import com.xiaomanjun.sleepdownschedule.feature.importing.PendingImportSetupStore
import com.xiaomanjun.sleepdownschedule.feature.importing.StudentTableImportException
import com.xiaomanjun.sleepdownschedule.feature.importing.StudentTableParser
import com.xiaomanjun.sleepdownschedule.transition.ActivityTransitionCoordinator
import com.xiaomanjun.sleepdownschedule.transition.CrossActivityTransitionHost
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import androidx.lifecycle.viewModelScope
import com.xiaomanjun.sleepdownschedule.PeriodEntity
import com.xiaomanjun.sleepdownschedule.ScheduleConfigEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val InitialImportFilesExtra = "initial_import_files"

internal fun unifiedImportIntent(context: Context, files: List<Uri> = emptyList()): Intent =
    Intent(context, EduSchoolSelectActivity::class.java).apply {
        val uris = files.distinct()
        if (uris.isNotEmpty()) {
            clipData = ClipData.newRawUri("Schedule import", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

open class EduSchoolSelectActivityHost : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        ActivityTransitionCoordinator.prepareDestinationBeforeOnCreate(this)
        super.onCreate(savedInstanceState)
        ActivityTransitionCoordinator.installDestinationWindowBackground(this)
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            val app = application as CourseScheduleApp
            val viewModel: ScheduleViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = ScheduleViewModelFactory(app, app.repository)
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val form: UnifiedImportFormState = androidx.lifecycle.viewmodel.compose.viewModel()
            LaunchedEffect(form) {
                form.initialize(
                    intent.clipData?.let { clip ->
                        (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                    }.orEmpty()
                )
            }
            CourseScheduleTheme(config = state.config) {
                CrossActivityTransitionHost(
                    activity = this@EduSchoolSelectActivityHost,
                    handleSystemBack = !form.submitting,
                    sourceContent = {
                        HomeMenuActivitySourceFallback(
                            config = state.config,
                            highlightedRowIndex = 4
                        )
                    }
                ) { requestClose ->
                    UnifiedImportPage(
                        state = state,
                        viewModel = viewModel,
                        form = form,
                        onBack = requestClose,
                        onImported = { scheduleId ->
                            PendingImportSetupStore.put(this@EduSchoolSelectActivityHost, scheduleId)
                            requestClose()
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun UnifiedImportPage(
    state: AppState,
    viewModel: ScheduleViewModel,
    form: UnifiedImportFormState,
    onBack: () -> Unit,
    onImported: (Int) -> Unit,
    unifiedImport: Boolean = true,
    embedded: Boolean = false,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) form.pick(uri)
    }

    LaunchedEffect(form.pendingUri, state.loaded) {
        if (form.pendingUri == null) return@LaunchedEffect
        // 首帧时仓储可能尚未加载完成，等真正配置就绪后再解析，避免用默认作息建草稿。
        if (!state.loaded) return@LaunchedEffect
        form.startLoad(context, state.config, state.periods)
    }

    val pick = { launcher.launch(arrayOf("*/*")) }
    val pageContent: @Composable (Backdrop?) -> Unit = { backdrop ->
        val draft = form.draft
        if (draft != null) {
            ConfirmScheduleScreen(
                draft = draft,
                backdrop = backdrop,
                submitting = form.submitting,
                errorMessage = form.saveError,
                onCancel = { if (!form.submitting) form.draft = null },
                onConfirm = confirm@{ createNew ->
                    if (form.submitting) return@confirm
                    form.submitting = true
                    form.saveError = null
                    viewModel.importDraft(draft, createNew, onFailure = {
                        form.submitting = false
                        form.saveError = it.message ?: "保存失败，请重试"
                    }) { scheduleId ->
                        form.submitting = false
                        form.reset()
                        onImported(scheduleId)
                    }
                },
            )
        } else {
            StudentTablePickContent(
                form = form,
                onPick = pick,
            )
        }
    }

    if (embedded) {
        CouiSettingsTabletDetailPaneScaffold(
            title = "导入课表",
            config = state.config,
            content = pageContent,
        )
    } else {
        DetailActivityScaffold(
            title = "导入课表",
            config = state.config,
            onBack = { if (!form.submitting) onBack() },
            content = pageContent,
        )
    }
}

@Composable
private fun StudentTablePickContent(
    form: UnifiedImportFormState,
    onPick: () -> Unit,
) {
    val topPadding = detailContentTopPadding()
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 16.dp, top = topPadding, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("学生课表文件", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "在教务系统“学生课表”页面导出课表（通常是 .xls 或 .html 文件），然后在这里选择它。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                form.pickedName?.let { name ->
                    Text(
                        "已选择：$name",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                form.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (form.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在解析课表…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPick, enabled = !form.loading) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (form.pickedName == null) "选择文件" else "重新选择")
                    }
                    if (form.draft != null) {
                        OutlinedButton(onClick = { form.draft = null }, enabled = !form.loading) {
                            Text("返回")
                        }
                    }
                }
            }
        }
        Text(
            "解析后会先展示预览，确认无误再写入课表。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(4.dp))
    }
}

internal class UnifiedImportFormState(private val savedState: SavedStateHandle) : ViewModel() {
    private var initialized = false
    private var loadJob: Job? = null
    var pendingUri by mutableStateOf<Uri?>(null)
    var pickedName by mutableStateOf<String?>(null)
    var draft by mutableStateOf<ImportDraft?>(null)
    var submitting by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    fun initialize(files: List<Uri>) {
        if (initialized) return
        initialized = true
        files.firstOrNull()?.let { pick(it) }
    }

    /** 兼容旧调用点：文字口令不再支持，只取文件。 */
    fun openWith(@Suppress("UNUSED_PARAMETER") text: String?, files: List<Uri>) {
        reset()
        files.firstOrNull()?.let { pick(it) }
    }

    fun openWith(files: List<Uri>) = openWith(null, files)

    fun pick(uri: Uri) {
        reset()
        pendingUri = uri
    }

    /**
     * 在 ViewModel 作用域内读取并解析所选文件。
     *
     * 解析不能放在 Composable 的 LaunchedEffect 里：转场宿主替换内容时会把组合移出，
     * 进行中的协程被取消，错误被显示成 "The coroutine was canceled because it left the
     * composition"，而且重新进入组合后请求已丢失。这里由 ViewModel 持有任务，组合重建不受影响。
     */
    fun startLoad(context: Context, config: ScheduleConfigEntity, periods: List<PeriodEntity>) {
        val uri = pendingUri ?: return
        if (loading) return
        pendingUri = null
        loading = true
        error = null
        saveError = null
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val self = coroutineContext[Job]
            try {
                val (name, parsed) = withContext(Dispatchers.IO) {
                    val (fileName, text) = readScheduleDocument(context.applicationContext, uri)
                    fileName to StudentTableParser.parse(text, config, periods, fileName)
                }
                pickedName = name
                draft = parsed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                draft = null
                error = when (failure) {
                    is StudentTableImportException -> failure.message
                    is SecurityException -> "没有读取该文件的权限，请重新选择文件"
                    else -> "读取课表文件失败，请确认所选文件是教务系统导出的学生课表（.xls 或 .html）"
                }
            } finally {
                if (loadJob === self) loading = false
            }
        }
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        draft = null
        saveError = null
        error = null
        loading = false
        submitting = false
    }
}

private suspend fun readScheduleDocument(context: Context, uri: Uri): Pair<String, String> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "课表文件"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_IMPORT_BYTES) error("文件超过 8MB，可能不是教务课表文件")
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        } ?: error("无法读取所选文件")
        name to decodeText(bytes)
    }

private fun decodeText(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
    }
    val utf8 = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (utf8 != null) return utf8
    return String(bytes, Charset.forName("GB18030"))
}

private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
