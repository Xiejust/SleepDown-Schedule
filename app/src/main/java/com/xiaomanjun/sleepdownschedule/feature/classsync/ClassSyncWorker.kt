package com.xiaomanjun.sleepdownschedule.feature.classsync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.xiaomanjun.sleepdownschedule.CourseScheduleApp
import java.util.concurrent.TimeUnit

/** 后台定时同步。系统会把周期任务限制在 15 分钟以上。 */
class ClassSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? CourseScheduleApp ?: return Result.success()
        val manager = ClassSyncManager(app, app.repository)
        return try {
            manager.onAppStart()
            Result.success()
        } catch (error: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WorkName = "sleepdown_class_sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<ClassSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WorkName, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkName)
        }
    }
}