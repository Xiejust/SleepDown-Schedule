package com.xiaomanjun.sleepdownschedule.feature.backup

import android.content.Context
import java.io.File
import com.xiaomanjun.sleepdownschedule.feature.agent.DayAgentPreferences
import com.xiaomanjun.sleepdownschedule.core.identity.AppIconManager

object BackupPreferencesApplier {
    fun apply(
        context: Context,
        preferences: BackupPreferences,
        scheduleRoomIdsByStableId: Map<String, Int>,
        finalAssetFilesById: Map<String, File> = emptyMap()
    ) {
        preferences.materialLevel?.let {
            com.xiaomanjun.sleepdownschedule.core.performance.AppMaterialPreferences.setLevel(context,
                com.xiaomanjun.sleepdownschedule.core.performance.restoredMaterialLevel(
                    com.xiaomanjun.sleepdownschedule.core.performance.AppMaterialPreferences.level,
                    com.xiaomanjun.sleepdownschedule.core.performance.AppMaterialLevel.valueOf(it)))
        }
        preferences.appIcon?.let { AppIconManager.applyBackupPreferences(context, it) }
        preferences.dayAgent?.let {
            DayAgentPreferences.applyBackupPreferences(context, it, scheduleRoomIdsByStableId)
        }
        // Scheme preferences from older backups are imported with the Room transaction. Replaying
        // preference finalization must not merge a second copy or resurrect removed schemes.
        preferences.courseQuietSettings?.let {
            com.xiaomanjun.sleepdownschedule.feature.reminder.CourseQuietPreferences.write(context, it)
        }
    }
}
