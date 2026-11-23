package com.xiaomanjun.sleepdownschedule.feature.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.kyant.backdrop.Backdrop
import com.xiaomanjun.sleepdownschedule.ImportDraft
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertAction
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertActionStyle
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertActions
import com.xiaomanjun.sleepdownschedule.core.ui.interaction.SleepDownLazyColumn
import com.xiaomanjun.sleepdownschedule.domain.schedule.resolvedCoursesForPreview
import com.xiaomanjun.sleepdownschedule.domain.schedule.withCompatiblePeriodGrid
import com.xiaomanjun.sleepdownschedule.feature.home.day.ImportPreviewCourseCard

private fun importDraftConfigurationSummary(draft: ImportDraft): String = buildList {
    add("${draft.config.totalWeeks} 周")
    add("${draft.periods.size} 个节次")
    draft.config.termStartDate?.takeIf(String::isNotBlank)?.let { add("开学 $it") }
    add("每节 ${draft.config.classDurationMinutes} 分钟")
    add("课间 ${draft.config.breakDurationMinutes} 分钟")
}.joinToString(" · ")

@Composable
fun ConfirmScheduleScreen(
    draft: ImportDraft,
    warning: String? = null,
    submitting: Boolean = false,
    errorMessage: String? = null,
    backdrop: Backdrop? = null,
    onCancel: () -> Unit,
    listHeader: (@Composable () -> Unit)? = null,
    onConfirm: (Boolean) -> Unit
) {
    val compatibility = remember(draft) { runCatching { draft.withCompatiblePeriodGrid() } }
    val previewDraft = remember(draft) {
        val normalized = com.xiaomanjun.sleepdownschedule.domain.schedule.normalizeImportMajorityBaseline(
            compatibility.getOrDefault(draft),
            requireZeroBaselineFollowers = true,
        )
        normalized.let { compatible -> compatible.copy(
            periods = compatible.periods,
            courses = compatible.courses.map {
                it.copy(
                    periods = it.periods.distinct().sorted(),
                    weeks = it.weeks.distinct().sorted()
                )
            }
        ) }
    }
    val previewCourses = remember(previewDraft) { previewDraft.resolvedCoursesForPreview() }
    val navigationInset = if (listHeader != null) regularImportNavigationInset() else 0.dp
    SleepDownLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = if (listHeader == null) 16.dp else 0.dp, bottom = 16.dp + navigationInset),
        overscrollEffect = if (listHeader != null) rememberCouiOverscrollEffect() else rememberOverscrollEffect(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (listHeader != null) item(key = "import_form") { listHeader() }
        item { Text("即将导入 " + previewDraft.courses.size + " 门课程，请确认后写入课表。") }
        itemsIndexed(previewDraft.notices) { _, notice -> Text(notice, style = MaterialTheme.typography.bodyMedium) }
        (errorMessage ?: compatibility.exceptionOrNull()?.message)?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (submitting) item { Text("正在保存课表…") }
        warning?.let { text ->
            item { Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        importConflictNotice(previewDraft)?.let { text ->
            item { Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        item { Text(importDraftConfigurationSummary(previewDraft)) }
        if (previewDraft.courses.isEmpty()) {
            item { Text("没有解析到课程", color = MaterialTheme.colorScheme.error) }
        } else {
            itemsIndexed(
                previewCourses,
                key = { index, course ->
                    "preview_${index}_${course.name}_${course.weekday}_${course.periods.joinToString("_")}_${course.weeks.take(3).joinToString("_")}"
                }
            ) { _, course ->
                ImportPreviewCourseCard(course, previewDraft.periods, previewDraft.config)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("请选择导入方式：", style = MaterialTheme.typography.bodyMedium)
                LiquidAlertActions(
                    actions = listOf(
                        LiquidAlertAction("创建新课表", LiquidAlertActionStyle.Primary, enabled = !submitting) { onConfirm(true) },
                        LiquidAlertAction("覆盖当前课表", LiquidAlertActionStyle.Destructive, enabled = !submitting) { onConfirm(false) },
                        LiquidAlertAction("取消", LiquidAlertActionStyle.Cancel, enabled = !submitting, onClick = onCancel)
                    ),
                    backdrop = backdrop,
                    config = previewDraft.config
                )
            }
        }
    }
}

@Composable
internal fun regularImportNavigationInset() = with(LocalDensity.current) {
    (WindowInsets.navigationBars.getBottom(this) - WindowInsets.ime.getBottom(this)).coerceAtLeast(0).toDp()
}
