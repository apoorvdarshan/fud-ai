package com.apoorvdarshan.calorietracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Retries Glance rendering after launcher/OEM background scheduling interruptions. */
class WidgetRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "Widget refresh started (attempt ${runAttemptCount + 1})")
        if (!WidgetRefreshScheduler.hasInstalledWidgets(applicationContext)) {
            Log.i(TAG, "Widget refresh skipped because no widgets are installed")
            return Result.success()
        }

        return if (WidgetUpdateCoordinator.updateAll(applicationContext)) {
            Log.i(TAG, "Widget refresh requests completed")
            Result.success()
        } else if (runAttemptCount < MAX_RETRIES) {
            Log.w(TAG, "Widget refresh will retry")
            Result.retry()
        } else {
            Log.e(TAG, "Widget refresh exhausted retries")
            Result.failure()
        }
    }

    private companion object {
        const val TAG = "FudAIWidget"
        const val MAX_RETRIES = 3
    }
}

object WidgetRefreshScheduler {
    private const val TAG = "FudAIWidget"
    private const val IMMEDIATE_WORK = "fud_ai_widget_refresh"
    private const val PERIODIC_WORK = "fud_ai_widget_periodic_refresh"

    private val receiverClasses = listOf(
        CalorieWidgetReceiver::class.java,
        ProteinWidgetReceiver::class.java,
        AllMetricsWidgetReceiver::class.java,
        WaterWidgetReceiver::class.java,
        VoiceAssistantWidgetReceiver::class.java,
        MealVoiceWidgetReceiver::class.java
    )

    fun onAppStarted(context: Context) {
        if (!hasInstalledWidgets(context)) return
        ensurePeriodic(context)
        enqueueImmediate(context, "app_start")
    }

    fun onWidgetEnabled(context: Context) {
        ensurePeriodic(context)
        enqueueImmediate(context, "widget_enabled")
    }

    fun enqueueImmediate(context: Context, reason: String) {
        Log.i(TAG, "Enqueueing widget refresh: $reason")
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun stopIfUnused(context: Context) {
        if (hasInstalledWidgets(context)) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_WORK)
    }

    fun hasInstalledWidgets(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return receiverClasses.any { receiver ->
            manager.getAppWidgetIds(ComponentName(context, receiver)).isNotEmpty()
        }
    }
}
