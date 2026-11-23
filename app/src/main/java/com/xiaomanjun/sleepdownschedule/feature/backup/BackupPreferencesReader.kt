package com.xiaomanjun.sleepdownschedule.feature.backup

import com.xiaomanjun.sleepdownschedule.*
import com.xiaomanjun.sleepdownschedule.feature.agent.*
import com.xiaomanjun.sleepdownschedule.core.identity.AppIconManager
import android.content.Context

data class BackupPreferencesExport(
    val preferences: BackupPreferences,
    val additionalAssets: List<BackupAsset> = emptyList()
)

/**
 * Read-only adapter for non-Room user preferences. It exports only protocol-safe fields; API keys
 * and other credential material are never loaded into the result.
 */
object BackupPreferencesReader {
    fun read(
        context: Context,
        scheduleStableIdsByRoomId: Map<Int, String>
    ): BackupPreferencesExport = BackupPreferencesExport(
        preferences = BackupPreferences(
            preferencesVersion = BackupFormatV1.PREFERENCES_VERSION,
            materialLevel = com.xiaomanjun.sleepdownschedule.core.performance.AppMaterialPreferences.level.name,
            appIcon = AppIconManager.backupPreferences(context),
            dayAgent = DayAgentPreferences.backupPreferences(context, scheduleStableIdsByRoomId),
            courseQuietSettings = com.xiaomanjun.sleepdownschedule.feature.reminder.CourseQuietPreferences.read(context)
        )
    )
}
