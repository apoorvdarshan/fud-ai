package com.apoorvdarshan.calorietracker.services.health

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import kotlinx.coroutines.flow.first
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only IDs enter WorkManager; retries always load the latest local value, never an old payload. */
class HealthWriteRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString("kind") ?: return Result.failure()
        val id = runCatching { UUID.fromString(inputData.getString("id")) }.getOrNull()
            ?: return Result.failure()
        val prefs = PreferencesStore(applicationContext)
        val health = HealthConnectManager(applicationContext, scheduleRetries = false)
        val enabled = prefs.healthConnectEnabled.first()
        val deletionRequested = inputData.getBoolean("delete", false)
        if (!enabled && !deletionRequested) return Result.success()
        if (!health.isAvailable()) {
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
        val success = when (kind) {
            "nutrition" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasNutritionWrite() },
                latest = { prefs.foodEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeNutrition(it) },
                delete = { health.deleteNutrition(id) }
            )
            "weight" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasWeightWrite() },
                latest = { prefs.weightEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeWeight(it) },
                delete = { health.deleteWeight(id) }
            )
            "bodyFat" -> retryLatestHealthEntry(
                enabled, deletionRequested,
                permitted = { health.hasBodyFatWrite() },
                latest = { prefs.bodyFatEntries.first().firstOrNull { it.id == id } },
                upsert = { health.writeBodyFat(it) },
                delete = { health.deleteBodyFat(id) }
            )
            else -> return Result.failure()
        }
        if (success) return Result.success()
        if (runAttemptCount < 5) return Result.retry()
        Log.w("FudAIHealth", "Health Connect write retries exhausted ($kind)")
        return Result.failure()
    }

    companion object {
        fun enqueue(context: Context, kind: String, id: UUID, delete: Boolean) {
            val request = OneTimeWorkRequestBuilder<HealthWriteRetryWorker>()
                .setInputData(workDataOf("kind" to kind, "id" to id.toString(), "delete" to delete))
                .setInitialDelay(10, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // Serial work for an entity prevents concurrent retry workers. New
            // mutations can still enqueue after a failed or cancelled retry.
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "health_write_${kind}_$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request
            )
        }
    }
}
