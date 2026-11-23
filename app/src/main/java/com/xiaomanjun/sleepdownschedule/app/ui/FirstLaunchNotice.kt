package com.xiaomanjun.sleepdownschedule.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertAction
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertActionStyle
import com.xiaomanjun.sleepdownschedule.core.ui.designsystem.LiquidAlertDialog
import com.xiaomanjun.sleepdownschedule.model.ScheduleConfigEntity

internal object FirstLaunchNoticeStore {
    private const val PreferencesName = "first_launch_notice"
    private const val ShownKey = "notice_v1_shown"

    fun hasShown(context: Context): Boolean =
        preferences(context).getBoolean(ShownKey, false)

    fun markShown(context: Context) {
        preferences(context).edit().putBoolean(ShownKey, true).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
}

private const val FirstLaunchNoticeMessage =
    "当前软件基于GitHub上的开源项目sleep-down课程表魔改开发而来，使用时遇到问题请联系，qq：2979958015"

/** 首次启动时展示一次来源与联系方式公告。 */
@Composable
internal fun FirstLaunchNoticeDialog(
    config: ScheduleConfigEntity,
    ready: Boolean
) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !FirstLaunchNoticeStore.hasShown(context)) visible = true
    }
    if (!visible) return
    LiquidAlertDialog(
        title = "公告",
        message = FirstLaunchNoticeMessage,
        actions = listOf(
            LiquidAlertAction("我知道了", LiquidAlertActionStyle.Primary) {
                FirstLaunchNoticeStore.markShown(context)
                visible = false
            }
        ),
        config = config,
        onDismissRequest = {
            FirstLaunchNoticeStore.markShown(context)
            visible = false
        }
    )
}