package expo.modules.healthsync

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

class HealthSyncModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("HealthSync")

    Constants {
      mapOf("isAvailable" to isHealthConnectAvailable())
    }

    AsyncFunction("authorizationStatus") {
      if (!isHealthConnectAvailable()) return@AsyncFunction "unavailable"
      val granted = clientOrNull()?.permissionController?.getGrantedPermissions() ?: emptySet()
      when {
        granted.containsAll(requiredPermissions) -> "authorized"
        granted.isEmpty() -> "notDetermined"
        else -> "denied"
      }
    }

    AsyncFunction("requestAuthorization") {
      if (!isHealthConnectAvailable()) return@AsyncFunction false
      val activity = appContext.currentActivity ?: return@AsyncFunction false
      val client = clientOrNull() ?: return@AsyncFunction false
      try {
        val contract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
        activity.startActivity(contract.createIntent(activity, requiredPermissions))
        client.permissionController.getGrantedPermissions().containsAll(requiredPermissions)
      } catch (_: Exception) {
        false
      }
    }

    AsyncFunction("writeWeight") { kg: Double, dateMs: Double, entryId: String? ->
      val client = clientOrNull() ?: return@AsyncFunction
      val time = Instant.ofEpochMilli(dateMs.toLong())
      val record = WeightRecord(
        time = time,
        zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
        weight = Mass.kilograms(kg),
        metadata = metadata(entryId)
      )
      withContext(Dispatchers.IO) { client.insertRecords(listOf(record)) }
    }

    AsyncFunction("writeBodyFat") { fraction: Double, dateMs: Double, entryId: String? ->
      val client = clientOrNull() ?: return@AsyncFunction
      val time = Instant.ofEpochMilli(dateMs.toLong())
      val record = BodyFatRecord(
        time = time,
        zoneOffset = ZoneId.systemDefault().rules.getOffset(time),
        percentage = Percentage(fraction * 100.0),
        metadata = metadata(entryId)
      )
      withContext(Dispatchers.IO) { client.insertRecords(listOf(record)) }
    }

    AsyncFunction("writeNutrition") { payload: Map<String, Any?> ->
      val client = clientOrNull() ?: return@AsyncFunction
      val dateMs = (payload["dateMs"] as? Number)?.toLong() ?: return@AsyncFunction
      val time = Instant.ofEpochMilli(dateMs)
      val record = NutritionRecord(
        startTime = time,
        startZoneOffset = ZoneId.systemDefault().rules.getOffset(time),
        endTime = time,
        endZoneOffset = ZoneId.systemDefault().rules.getOffset(time),
        energy = (payload["calories"] as? Number)?.toDouble()?.let { Energy.kilocalories(it) },
        protein = (payload["protein"] as? Number)?.toDouble()?.let { Mass.grams(it) },
        totalCarbohydrate = (payload["carbs"] as? Number)?.toDouble()?.let { Mass.grams(it) },
        totalFat = (payload["fat"] as? Number)?.toDouble()?.let { Mass.grams(it) },
        name = payload["name"] as? String,
        metadata = metadata(payload["entryId"] as? String)
      )
      withContext(Dispatchers.IO) { client.insertRecords(listOf(record)) }
    }

    AsyncFunction("deleteNutrition") { entryId: String ->
      val client = clientOrNull() ?: return@AsyncFunction
      withContext(Dispatchers.IO) {
        client.deleteRecords(
          NutritionRecord::class,
          recordIdsList = emptyList(),
          clientRecordIdsList = listOf(entryId),
        )
      }
    }

    AsyncFunction("readSteps") { startMs: Double, endMs: Double ->
      val client = clientOrNull() ?: return@AsyncFunction null
      val request = ReadRecordsRequest(
        recordType = StepsRecord::class,
        timeRangeFilter = TimeRangeFilter.between(
          Instant.ofEpochMilli(startMs.toLong()),
          Instant.ofEpochMilli(endMs.toLong())
        )
      )
      val records = withContext(Dispatchers.IO) { client.readRecords(request).records }
      records.sumOf { it.count }.toInt()
    }
  }

  private val requiredPermissions = setOf(
    HealthPermission.getWritePermission(WeightRecord::class),
    HealthPermission.getWritePermission(BodyFatRecord::class),
    HealthPermission.getWritePermission(NutritionRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class),
  )

  private fun clientOrNull(): HealthConnectClient? {
    val context = appContext.reactContext ?: return null
    return try {
      if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
        HealthConnectClient.getOrCreate(context)
      } else null
    } catch (_: Exception) {
      null
    }
  }

  private fun isHealthConnectAvailable(): Boolean {
    val context = appContext.reactContext ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    return try {
      HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Exception) {
      false
    }
  }

  private fun metadata(entryId: String?): Metadata {
    return if (entryId != null) Metadata.manualEntry(clientRecordId = entryId) else Metadata.manualEntry()
  }
}
