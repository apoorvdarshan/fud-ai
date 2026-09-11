package com.apoorvdarshan.calorietracker.services.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.MealType as HCMealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.WeightEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Single boundary for Health Connect I/O. Port of iOS HealthKitManager.
 *
 * Conventions:
 * - Weight, body-fat, and nutrition samples carry [Metadata.clientRecordId] =
 *   "fudai_<uuid>" so we can dedup in-app vs external writes and delete our own
 *   records cleanly.
 * - Workout-burn samples use a distinct client id containing both the stable
 *   diary date and session UUID. Health Connect has no arbitrary metadata map,
 *   so encoding the date keeps a selected diary day stable across time zones and
 *   after a reinstall.
 * - Nutrition records include macros plus every optional nutrient Health Connect
 *   can represent from Fud AI's food model.
 * - The "typesVersion" integer bumps when we add new record types so existing
 *   users get a re-authorization prompt.
 */
enum class HealthConnectAvailability {
    AVAILABLE,
    PROFILE_UNSUPPORTED,
    PROVIDER_UPDATE_REQUIRED,
    UNAVAILABLE
}

/** Which settings/unavailable copy to show; null means Health Connect is usable. */
internal enum class HealthAvailabilityMessageKind {
    PROFILE_UNSUPPORTED,
    PROVIDER_UPDATE_REQUIRED,
    SYSTEM_UNAVAILABLE
}

internal fun healthAvailabilityMessageKind(
    availability: HealthConnectAvailability
): HealthAvailabilityMessageKind? = when (availability) {
    HealthConnectAvailability.AVAILABLE -> null
    HealthConnectAvailability.PROFILE_UNSUPPORTED -> HealthAvailabilityMessageKind.PROFILE_UNSUPPORTED
    HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED ->
        HealthAvailabilityMessageKind.PROVIDER_UPDATE_REQUIRED
    HealthConnectAvailability.UNAVAILABLE -> HealthAvailabilityMessageKind.SYSTEM_UNAVAILABLE
}

/**
 * Whether a nutrition write is permitted. [UNKNOWN] is deliberately distinct from [DENIED]:
 * it means the permission probe failed, not that the user said no. Treating the two the same
 * is what lets a transient Health Connect outage discard a food entry silently.
 */
enum class NutritionWriteGate {
    ALLOWED,
    DENIED,
    UNKNOWN
}

internal fun resolveHealthConnectAvailability(
    isProfile: Boolean,
    sdkAvailable: Boolean,
    providerUpdateRequired: Boolean
): HealthConnectAvailability = when {
    isProfile -> HealthConnectAvailability.PROFILE_UNSUPPORTED
    sdkAvailable -> HealthConnectAvailability.AVAILABLE
    providerUpdateRequired -> HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED
    else -> HealthConnectAvailability.UNAVAILABLE
}

class HealthConnectManager(
    private val context: Context,
    private val scheduleRetries: Boolean = true
) {

    private suspend fun writeWithRetry(
        kind: String, id: UUID, delete: Boolean = false, write: suspend () -> Unit
    ): Boolean = retryHealthWrite(
        deferred = {
            if (scheduleRetries) HealthWriteRetryWorker.enqueue(context, kind, id, delete)
        },
        failed = { error ->
            // Log only the failure class; records and health values stay private.
            android.util.Log.w("FudAIHealth", "Health Connect $kind write failed: ${error.javaClass.simpleName}")
        },
        write = write
    )

    private val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
    }

    /**
     * Health Connect rejects every secondary Android profile on Android 14+, including Work
     * Profile and Private Space. The system permission screen can still show Fud AI as approved,
     * so preserve that reason instead of misreporting the framework service as uninstalled.
     */
    fun availability(): HealthConnectAvailability {
        val isProfile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            runCatching {
                (context.getSystemService(Context.USER_SERVICE) as? UserManager)?.isProfile == true
            }.getOrDefault(false)
        val sdkStatus = HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PROVIDER_PACKAGE)
        val resolved = resolveHealthConnectAvailability(
            isProfile = isProfile,
            sdkAvailable = sdkStatus == HealthConnectClient.SDK_AVAILABLE,
            providerUpdateRequired =
                sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
        )
        logAvailabilityIfNeeded(sdkStatus, isProfile, resolved)
        return resolved
    }

    private fun logAvailabilityIfNeeded(
        sdkStatus: Int,
        isProfile: Boolean,
        resolved: HealthConnectAvailability
    ) {
        if (resolved == HealthConnectAvailability.AVAILABLE) {
            lastLoggedAvailabilityKey.set(null)
            return
        }
        val key = "$sdkStatus|$isProfile|$resolved"
        if (lastLoggedAvailabilityKey.getAndSet(key) == key) return
        android.util.Log.w(
            "FudAIHealth",
            "Health Connect availability sdkStatus=$sdkStatus isProfile=$isProfile resolved=$resolved"
        )
    }

    fun isAvailable(): Boolean = availability() == HealthConnectAvailability.AVAILABLE

    // Individual permission strings so each direction can be gated independently.
    // The old all-or-nothing gate meant a user who granted only READ (e.g. to pull
    // weigh-ins from a Withings scale) got no sync at all — see issue #91.
    private val weightRead = HealthPermission.getReadPermission(WeightRecord::class)
    private val weightWrite = HealthPermission.getWritePermission(WeightRecord::class)
    private val bodyFatRead = HealthPermission.getReadPermission(BodyFatRecord::class)
    private val bodyFatWrite = HealthPermission.getWritePermission(BodyFatRecord::class)
    private val nutritionRead = HealthPermission.getReadPermission(NutritionRecord::class)
    private val nutritionWrite = HealthPermission.getWritePermission(NutritionRecord::class)
    private val activeEnergyRead = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    private val activeEnergyWrite = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)
    private val totalEnergyRead = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    private val stepsRead = HealthPermission.getReadPermission(StepsRecord::class)
    private val backgroundRead = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val permissions: Set<String> = setOf(
        weightRead, weightWrite, nutritionRead, nutritionWrite,
        bodyFatRead, bodyFatWrite, activeEnergyRead, activeEnergyWrite, totalEnergyRead,
        stepsRead
    )

    /** Requested only when Daily Summary is enabled. Keeping it out of [permissions]
     *  avoids asking every Health Connect user for background access. */
    val dailySummaryPermissions: Set<String> = permissions + backgroundRead

    fun supportsNutritionHistory(): Boolean = runCatching {
        client?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    val nutritionImportPermissions: Set<String>
        get() = setOf(nutritionRead) + if (supportsNutritionHistory())
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY) else emptySet()

    suspend fun hasNutritionHistory(): Boolean = supportsNutritionHistory() &&
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted()

    /**
     * Granted permissions, or null when the permission probe itself failed.
     *
     * Health Connect answers over IPC, and the service can be mid-update, cold-starting,
     * or binder-timing-out — each of which throws here. Collapsing that into "nothing is
     * granted" makes a transient outage indistinguishable from a permission the user
     * actually revoked. That distinction matters on the write path: a revoked permission
     * means there is nothing to do, while a failed probe means we do not yet know, and
     * dropping the write is data loss the user never sees.
     */
    private suspend fun grantedOrNull(): Set<String>? =
        runCatching { client?.permissionController?.getGrantedPermissions() }
            .onFailure { Log.w(TAG, "Health Connect permission probe failed", it) }
            .getOrNull()

    /** Fail-closed view of [grantedOrNull] for the read paths, where "assume nothing is
     *  granted" is the safe answer — a read that returns nothing loses no data. */
    private suspend fun granted(): Set<String> = grantedOrNull() ?: emptySet()

    /**
     * Whether a nutrition write may proceed. [NutritionWriteGate.UNKNOWN] means the
     * permission probe failed, not that permission is missing — callers should attempt
     * the write anyway and treat a failure as retryable rather than skipping silently.
     */
    suspend fun nutritionWriteGate(): NutritionWriteGate = when (val g = grantedOrNull()) {
        null -> NutritionWriteGate.UNKNOWN
        else -> if (nutritionWrite in g) NutritionWriteGate.ALLOWED else NutritionWriteGate.DENIED
    }

    /** The "connected" state: at least one Fud AI permission granted. Partial grants
     *  are valid — a read-only user still syncs the read direction. */
    suspend fun hasAnyPermission(): Boolean = granted().any { it in permissions }

    suspend fun hasWeightRead(): Boolean = weightRead in granted()
    suspend fun hasWeightWrite(): Boolean = weightWrite in granted()
    suspend fun hasBodyFatRead(): Boolean = bodyFatRead in granted()
    suspend fun hasBodyFatWrite(): Boolean = bodyFatWrite in granted()
    suspend fun hasNutritionRead(): Boolean = nutritionRead in granted()
    /** Fail-closed, like its siblings. Do NOT gate a nutrition write on this: a failed
     *  probe reads as false here, which is how writes went missing. Use [nutritionWriteGate]. */
    suspend fun hasNutritionWrite(): Boolean = nutritionWrite in granted()
    suspend fun hasActiveEnergyRead(): Boolean = activeEnergyRead in granted()
    suspend fun hasActiveEnergyWrite(): Boolean = activeEnergyWrite in granted()
    suspend fun hasEnergyRead(): Boolean = granted().let { activeEnergyRead in it && totalEnergyRead in it }
    suspend fun hasStepsRead(): Boolean = stepsRead in granted()
    suspend fun hasBackgroundRead(): Boolean = backgroundRead in granted()

    /** One permission read snapshotting every capability — used by the read-sync coordinator. */
    suspend fun capabilities(): HealthCapabilities {
        val g = granted()
        return HealthCapabilities(
            weightRead = weightRead in g,
            weightWrite = weightWrite in g,
            bodyFatRead = bodyFatRead in g,
            bodyFatWrite = bodyFatWrite in g,
            nutritionRead = nutritionRead in g,
            nutritionWrite = nutritionWrite in g,
            energyRead = activeEnergyRead in g && totalEnergyRead in g,
            activeEnergyWrite = activeEnergyWrite in g,
            stepsRead = stepsRead in g
        )
    }

    /** True for records Fud AI itself wrote, so read-sync can tell them apart from
     *  external sources (change-token consumers skip them; the restore path keeps them). */
    fun isOwnRecord(clientRecordId: String?): Boolean =
        clientRecordId?.startsWith(CLIENT_PREFIX) == true

    /** The original in-app entry UUID embedded in one of our own clientRecordIds
     *  ("fudai_<uuid>"), or null for external/malformed tags. Restoring with the
     *  original id keeps future edits/deletes targeting the matching HC record. */
    fun ownRecordId(clientRecordId: String?): UUID? {
        if (clientRecordId == null || !clientRecordId.startsWith(CLIENT_PREFIX)) return null
        return runCatching { UUID.fromString(clientRecordId.removePrefix(CLIENT_PREFIX)) }.getOrNull()
    }

    /** Used to build the permission-request ActivityResultContract on the UI side. */
    fun permissionRequestContract() = PermissionController.createRequestPermissionResultContract()

    /**
     * Opens Health Connect's permission UI. Android 14+ supports an app-specific destination;
     * older devices use the standalone Health Connect app's settings screen.
     */
    fun manageAccessIntent(): Intent {
        val appSpecific = Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
            .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        val generic = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            appSpecific
        } else {
            generic
        }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Play Store listing for the standalone Health Connect app (pre-Android 14 devices). */
    fun playStoreIntent(): Intent {
        val marketUri = Uri.parse(
            "market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE&url=healthconnect%3A%2F%2Fonboarding"
        )
        return Intent(Intent.ACTION_VIEW, marketUri).apply {
            setPackage("com.android.vending")
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun playStoreWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_CONNECT_PLAY_STORE_WEB))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openPlayStore(): Boolean =
        runCatching { context.startActivity(playStoreIntent()) }.isSuccess ||
            runCatching { context.startActivity(playStoreWebIntent()) }.isSuccess

    // -- Weight -----------------------------------------------------------

    suspend fun writeWeight(entry: WeightEntry): Boolean {
        val c = client ?: return false
        val record = WeightRecord(
            time = entry.date,
            zoneOffset = null,
            weight = Mass.kilograms(entry.weightKg),
            metadata = Metadata.manualEntry(
                clientRecordId = tag(entry.id),
                clientRecordVersion = writeVersions.next()
            )
        )
        return writeWithRetry("weight", entry.id) { c.insertRecords(listOf(record)) }
    }

    suspend fun deleteWeight(entryId: UUID): Boolean {
        val c = client ?: return false
        return writeWithRetry("weight", entryId, delete = true) {
            c.deleteRecords(
                recordType = WeightRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(tag(entryId))
            )
        }
    }

    suspend fun readWeights(from: Instant, to: Instant): List<ExternalWeight> {
        val c = client ?: return emptyList()
        val out = mutableListOf<ExternalWeight>()
        var pageToken: String? = null
        // readRecords returns one page (default 1000); follow pageToken so a large
        // history isn't silently truncated to the first page.
        do {
            val response = runCatching {
                c.readRecords(
                    ReadRecordsRequest(
                        recordType = WeightRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = pageToken
                    )
                )
            }.getOrNull() ?: break
            response.records.forEach {
                out.add(
                    ExternalWeight(
                        time = it.time,
                        weightKg = it.weight.inKilograms,
                        clientRecordId = it.metadata.clientRecordId,
                        recordId = it.metadata.id
                    )
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    // -- Body fat ---------------------------------------------------------

    suspend fun writeBodyFat(entry: BodyFatEntry): Boolean {
        val c = client ?: return false
        val record = BodyFatRecord(
            time = entry.date,
            zoneOffset = null,
            // BodyFatRecord wants 0–100 percent, not a fraction.
            percentage = Percentage(entry.bodyFatFraction * 100),
            metadata = Metadata.manualEntry(
                clientRecordId = tag(entry.id),
                clientRecordVersion = writeVersions.next()
            )
        )
        return writeWithRetry("bodyFat", entry.id) { c.insertRecords(listOf(record)) }
    }

    suspend fun deleteBodyFat(entryId: UUID): Boolean {
        val c = client ?: return false
        return writeWithRetry("bodyFat", entryId, delete = true) {
            c.deleteRecords(
                recordType = BodyFatRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(tag(entryId))
            )
        }
    }

    suspend fun readBodyFats(from: Instant, to: Instant): List<ExternalBodyFat> {
        val c = client ?: return emptyList()
        val out = mutableListOf<ExternalBodyFat>()
        var pageToken: String? = null
        do {
            val response = runCatching {
                c.readRecords(
                    ReadRecordsRequest(
                        recordType = BodyFatRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = pageToken
                    )
                )
            }.getOrNull() ?: break
            response.records.forEach {
                out.add(
                    ExternalBodyFat(
                        time = it.time,
                        // Convert HC's 0–100 back to our 0–1 fraction convention.
                        bodyFatFraction = it.percentage.value / 100.0,
                        clientRecordId = it.metadata.clientRecordId,
                        recordId = it.metadata.id
                    )
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    // -- Nutrition --------------------------------------------------------

    suspend fun writeNutrition(entry: FoodEntry): Boolean {
        if (entry.healthConnectOrigin != null) return true
        val c = client ?: return false
        val start = entry.timestamp
        if (start.isAfter(Instant.now())) return false
        // Nutrition records need a non-zero duration or Health Connect rejects them; use 1 minute.
        val end = start.plusSeconds(60)
        // Keep the same version across retries so an older in-flight write
        // cannot overwrite a later edit that has already reached Health Connect.
        val version = writeVersions.next()
        return writeWithRetry("nutrition", entry.id) {
            val record = NutritionRecord(
                startTime = start,
                endTime = end,
                startZoneOffset = null,
                endZoneOffset = null,
                name = entry.name,
                mealType = mealTypeFor(entry.mealType),
                energy = Energy.kilocalories(entry.calories.toDouble()),
                protein = Mass.grams(entry.protein),
                totalCarbohydrate = Mass.grams(entry.carbs),
                totalFat = Mass.grams(entry.fat),
                dietaryFiber = entry.fiber?.let { Mass.grams(it) },
                sugar = entry.sugar?.let { Mass.grams(it) },
                saturatedFat = entry.saturatedFat?.let { Mass.grams(it) },
                monounsaturatedFat = entry.monounsaturatedFat?.let { Mass.grams(it) },
                polyunsaturatedFat = entry.polyunsaturatedFat?.let { Mass.grams(it) },
                transFat = entry.transFat?.let { Mass.grams(it) },
                cholesterol = entry.cholesterol?.let { Mass.milligrams(it) },
                caffeine = entry.caffeine?.let { Mass.milligrams(it) },
                sodium = entry.sodium?.let { Mass.milligrams(it) },
                potassium = entry.potassium?.let { Mass.milligrams(it) },
                calcium = entry.calcium?.let { Mass.milligrams(it) },
                iron = entry.iron?.let { Mass.milligrams(it) },
                magnesium = entry.magnesium?.let { Mass.milligrams(it) },
                zinc = entry.zinc?.let { Mass.milligrams(it) },
                vitaminA = entry.vitaminA?.let { Mass.micrograms(it) },
                vitaminC = entry.vitaminC?.let { Mass.milligrams(it) },
                vitaminD = entry.vitaminD?.let { Mass.micrograms(it) },
                vitaminB12 = entry.vitaminB12?.let { Mass.micrograms(it) },
                vitaminE = entry.vitaminE?.let { Mass.milligrams(it) },
                vitaminK = entry.vitaminK?.let { Mass.micrograms(it) },
                folate = entry.folate?.let { Mass.micrograms(it) },
                metadata = Metadata.manualEntry(
                    clientRecordId = tag(entry.id),
                    clientRecordVersion = version
                )
            )
            c.insertRecords(listOf(record))
        }
    }

    suspend fun updateNutrition(entry: FoodEntry): Boolean {
        // Versioned upsert preserves the existing record if a write fails and
        // emits one change for readers instead of a deletion plus an insertion.
        return writeNutrition(entry)
    }

    suspend fun deleteNutrition(entryId: UUID): Boolean {
        val c = client ?: return false
        return writeWithRetry("nutrition", entryId, delete = true) {
            c.deleteRecords(
                recordType = NutritionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(tag(entryId))
            )
        }
    }

    /** All NutritionRecords in the range, mapped back to Fud AI's units (the exact
     *  inverse of [writeNutrition]). Powers the food-log restore after a reinstall
     *  or new phone, where Health Connect data survives but app storage doesn't.
     *  Returns null when any page read fails (rate limit, binder error) so the
     *  caller can leave its one-shot flag unset and retry, instead of treating a
     *  partial read as the complete history. */
    suspend fun readNutrition(from: Instant, to: Instant): List<ExternalNutrition>? {
        val c = client ?: return null
        val out = mutableListOf<ExternalNutrition>()
        var pageToken: String? = null
        do {
            val response = runCatching {
                c.readRecords(
                    ReadRecordsRequest(
                        recordType = NutritionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        pageToken = pageToken
                    )
                )
            }.getOrNull() ?: return null
            response.records.forEach {
                out.add(
                    ExternalNutrition(
                        time = it.startTime,
                        name = it.name,
                        mealType = mealTypeFrom(it.mealType),
                        calories = it.energy?.inKilocalories,
                        protein = it.protein?.inGrams,
                        carbs = it.totalCarbohydrate?.inGrams,
                        fat = it.totalFat?.inGrams,
                        fiber = it.dietaryFiber?.inGrams,
                        sugar = it.sugar?.inGrams,
                        saturatedFat = it.saturatedFat?.inGrams,
                        monounsaturatedFat = it.monounsaturatedFat?.inGrams,
                        polyunsaturatedFat = it.polyunsaturatedFat?.inGrams,
                        transFat = it.transFat?.inGrams,
                        cholesterol = it.cholesterol?.inMilligrams,
                        caffeine = it.caffeine?.inMilligrams,
                        sodium = it.sodium?.inMilligrams,
                        potassium = it.potassium?.inMilligrams,
                        calcium = it.calcium?.inMilligrams,
                        iron = it.iron?.inMilligrams,
                        magnesium = it.magnesium?.inMilligrams,
                        zinc = it.zinc?.inMilligrams,
                        vitaminA = it.vitaminA?.inMicrograms,
                        vitaminC = it.vitaminC?.inMilligrams,
                        vitaminD = it.vitaminD?.inMicrograms,
                        vitaminB12 = it.vitaminB12?.inMicrograms,
                        vitaminE = it.vitaminE?.inMilligrams,
                        vitaminK = it.vitaminK?.inMicrograms,
                        folate = it.folate?.inMicrograms,
                        clientRecordId = it.metadata.clientRecordId,
                        recordId = it.metadata.id,
                        originPackage = it.metadata.dataOrigin.packageName
                    )
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return out
    }

    // -- Workout burn ------------------------------------------------------

    /**
     * Inserts or replaces the active-energy estimate for one calculated diary
     * session. [Metadata.clientRecordVersion] makes this an atomic upsert: Health
     * Connect keeps only the highest version for this app, record type, and stable
     * client id even when rapid recalculations finish out of order.
     *
     * The diary date is represented at local noon for a non-zero one-minute
     * interval. Its canonical identity still comes from the encoded
     * [diaryDateKey], so a later time-zone change cannot move the restored day.
     */
    suspend fun upsertWorkoutBurn(
        sessionId: UUID,
        diaryDateKey: String,
        caloriesBurned: Int,
        healthSyncVersion: Int
    ): Boolean {
        val c = client ?: return false
        if (caloriesBurned !in 1..MAX_WORKOUT_BURN_CALORIES || healthSyncVersion < 1) return false
        val diaryDate = parseDiaryDateKey(diaryDateKey) ?: return false
        val clientRecordId = workoutBurnClientRecordId(diaryDateKey, sessionId) ?: return false
        val zone = ZoneId.systemDefault()
        val start = diaryDate.atTime(12, 0).atZone(zone)
        val end = start.plusMinutes(1)
        val record = ActiveCaloriesBurnedRecord(
            startTime = start.toInstant(),
            startZoneOffset = start.offset,
            endTime = end.toInstant(),
            endZoneOffset = end.offset,
            energy = Energy.kilocalories(caloriesBurned.toDouble()),
            metadata = Metadata.manualEntry(
                clientRecordId = clientRecordId,
                clientRecordVersion = healthSyncVersion.toLong()
            )
        )
        return runCatching { c.insertRecords(listOf(record)) }.isSuccess
    }

    /** Deletes exactly the app-owned burn sample for this stable diary session. */
    suspend fun deleteWorkoutBurn(sessionId: UUID, diaryDateKey: String): Boolean {
        val clientRecordId = workoutBurnClientRecordId(diaryDateKey, sessionId) ?: return false
        return deleteWorkoutBurn(clientRecordId)
    }

    /**
     * Exact-id deletion variant for repositories that persist the Health client
     * id alongside a tombstone. Invalid or non-workout ids are never forwarded to
     * Health Connect.
     */
    suspend fun deleteWorkoutBurn(clientRecordId: String): Boolean {
        val c = client ?: return false
        if (parseWorkoutBurnClientRecordId(clientRecordId) == null) return false
        return runCatching {
            c.deleteRecords(
                recordType = ActiveCaloriesBurnedRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = listOf(clientRecordId)
            )
        }.isSuccess
    }

    /**
     * Reads only active-energy records authored by this installed package and
     * returns only well-formed Fud AI workout-burn samples. Null means the query
     * failed; an empty list is a successful query with no owned burns.
     */
    suspend fun readOwnedWorkoutBurns(from: Instant, to: Instant): List<HealthWorkoutBurn>? {
        val c = client ?: return null
        if (!from.isBefore(to)) return emptyList()
        val out = mutableListOf<HealthWorkoutBurn>()
        val ownOrigin = setOf(DataOrigin(context.packageName))
        var pageToken: String? = null
        do {
            val response = runCatching {
                c.readRecords(
                    ReadRecordsRequest(
                        recordType = ActiveCaloriesBurnedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                        dataOriginFilter = ownOrigin,
                        pageToken = pageToken
                    )
                )
            }.getOrNull() ?: return null
            response.records.forEach { record ->
                val clientRecordId = record.metadata.clientRecordId ?: return@forEach
                val identity = parseWorkoutBurnClientRecordId(clientRecordId) ?: return@forEach
                val version = record.metadata.clientRecordVersion
                    .takeIf { it in 1..Int.MAX_VALUE.toLong() }
                    ?.toInt()
                    ?: return@forEach
                val rawCalories = record.energy.inKilocalories
                if (!rawCalories.isFinite() || rawCalories <= 0.0) return@forEach
                val calories = rawCalories.roundToInt()
                if (calories !in 1..MAX_WORKOUT_BURN_CALORIES) return@forEach
                out.add(
                    HealthWorkoutBurn(
                        sessionId = identity.sessionId,
                        diaryDateKey = identity.diaryDateKey,
                        caloriesBurned = calories,
                        healthSyncVersion = version,
                        startTime = record.startTime,
                        endTime = record.endTime,
                        clientRecordId = clientRecordId,
                        recordId = record.metadata.id
                    )
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)
        return out.sortedWith(compareBy<HealthWorkoutBurn> { it.diaryDateKey }.thenBy { it.healthSyncVersion })
    }

    // -- Energy burn summary --------------------------------------------

    /** Completed-day energy history for goal evidence. The active-calorie value has this app's data
     *  origin subtracted, so locally estimated workout burns cannot feed back into goal math. */
    suspend fun readRecentDailyEnergy(days: Int = 14): List<HealthDatedEnergy> {
        val requestedDays = days.coerceIn(1, 90)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return buildList {
            for (offset in requestedDays downTo 1) {
                val date = today.minusDays(offset.toLong())
                val start = date.atStartOfDay(zone).toInstant()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant()
                val daily = readDailyEnergy(start, end) ?: continue
                add(
                    HealthDatedEnergy(
                        date = date,
                        activeCalories = daily.active.roundToInt(),
                        totalCalories = daily.total?.roundToInt()
                    )
                )
            }
        }
    }

    suspend fun readRecentEnergySummary(days: Int = 14): HealthEnergySummary? {
        val requestedDays = maxOf(3, days)
        val daily = readRecentDailyEnergy(requestedDays)

        if (daily.size < 3) return null

        val activeAverage = daily.sumOf { it.activeCalories.toDouble() } / daily.size
        val totalValues = daily.mapNotNull { it.totalCalories }
        val totalAverage = totalValues.takeIf { it.size >= 3 }?.average()
        val basalAverage = totalAverage?.let { maxOf(0.0, it - activeAverage) }
        return HealthEnergySummary(
            activeAverageCalories = activeAverage.roundToInt(),
            basalAverageCalories = basalAverage?.roundToInt(),
            totalAverageCalories = totalAverage?.roundToInt(),
            daysUsed = daily.size,
            requestedDays = requestedDays
        )
    }

    /** Measured burn for one local calendar day. Used by the existing nightly
     *  notification; callers fall back to its current static text when Health
     *  Connect cannot be read in the background. */
    suspend fun readEnergyForDay(date: LocalDate): HealthDailyEnergy? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        if (!end.isAfter(start)) return null
        val daily = readDailyEnergy(start, end) ?: return null
        return HealthDailyEnergy(
            activeCalories = daily.active.roundToInt(),
            totalCalories = daily.total?.roundToInt()
        )
    }

    /** Daily step total for one local calendar day from Health Connect aggregates. */
    suspend fun readStepsForDay(date: LocalDate): Int? {
        if (!hasStepsRead()) return null
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), Instant.now())
        if (!end.isAfter(start)) return null
        return readDailySteps(start, end)
    }

    private suspend fun readDailySteps(start: Instant, end: Instant): Int? {
        val c = client ?: return null
        val result = runCatching {
            c.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
        }.getOrNull() ?: return null
        return result[StepsRecord.COUNT_TOTAL]?.toLong()?.toInt()?.takeIf { it >= 0 }
    }

    private suspend fun readDailyEnergy(start: Instant, end: Instant): DailyEnergy? {
        val c = client ?: return null
        val result = runCatching {
            c.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )
        }.getOrNull() ?: return null

        // Fud AI's workout burns are estimates. Keep them in Health Connect,
        // but subtract this app's origin so the nightly balance cannot count
        // the same workout once as measured burn and again as an app estimate.
        val ownActiveResult = runCatching {
            c.aggregate(
                AggregateRequest(
                    metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = setOf(DataOrigin(context.packageName))
                )
            )
        }.getOrNull() ?: return null

        val allActive = result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        val ownActive = ownActiveResult[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
        val active = externalActiveCalories(allActive, ownActive)
        val total = externalTotalCalories(
            rawTotal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            allActive = allActive,
            ownActive = ownActive
        )
        // Basal/total-only dates are not evidence of daily activity expenditure and can anchor
        // goals near BMR. Require a positive external active-energy signal for this day.
        if (active <= 0.0) return null
        return DailyEnergy(active = active, total = total)
    }

    // -- Change observation (external weight imports) --------------------

    /** Opaque token used to fetch incremental changes. Call once, persist, pass back later.
     *  Now watches both Weight and BodyFat records — a single token reflects upserts of either. */
    suspend fun getChangesToken(
        recordTypes: Set<kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>> =
            setOf(WeightRecord::class, BodyFatRecord::class)
    ): String? {
        val c = client ?: return null
        if (recordTypes.isEmpty()) return null
        return runCatching {
            c.getChangesToken(
                androidx.health.connect.client.request.ChangesTokenRequest(recordTypes = recordTypes)
            )
        }.getOrNull()
    }

    /** Returns observed external weight upserts since [sinceToken] plus the next token to use.
     *  Returns null when the token is expired or invalid so the caller re-backfills from scratch
     *  (an expired token is a *successful* response with changesTokenExpired=true, not an exception). */
    suspend fun consumeWeightChanges(sinceToken: String): Pair<List<ExternalWeight>, String?>? {
        val c = client ?: return null
        val results = mutableListOf<ExternalWeight>()
        var token = sinceToken
        // getChanges returns one page; drain hasMore so we don't truncate a large backlog.
        while (true) {
            val changes = runCatching { c.getChanges(token) }.getOrNull() ?: return null
            if (changes.changesTokenExpired) return null
            changes.changes.filterIsInstance<UpsertionChange>().forEach { change ->
                val rec = change.record as? WeightRecord ?: return@forEach
                // Skip samples we wrote ourselves (prefix matches our tag).
                val cid = rec.metadata.clientRecordId
                if (cid != null && cid.startsWith(CLIENT_PREFIX)) return@forEach
                results.add(
                    ExternalWeight(
                        time = rec.time,
                        weightKg = rec.weight.inKilograms,
                        clientRecordId = cid,
                        recordId = rec.metadata.id
                    )
                )
            }
            token = changes.nextChangesToken
            if (!changes.hasMore) break
        }
        return results to token
    }

    /** Sibling of [consumeWeightChanges] for BodyFat records. The combined
     *  changes-token watches both record types, so callers should drain both
     *  consumers using the SAME nextChangesToken returned by either call.
     *  We expose them as separate functions only to keep each result strongly typed. */
    suspend fun consumeBodyFatChanges(sinceToken: String): Pair<List<ExternalBodyFat>, String?>? {
        val c = client ?: return null
        val results = mutableListOf<ExternalBodyFat>()
        var token = sinceToken
        while (true) {
            val changes = runCatching { c.getChanges(token) }.getOrNull() ?: return null
            if (changes.changesTokenExpired) return null
            changes.changes.filterIsInstance<UpsertionChange>().forEach { change ->
                val rec = change.record as? BodyFatRecord ?: return@forEach
                val cid = rec.metadata.clientRecordId
                if (cid != null && cid.startsWith(CLIENT_PREFIX)) return@forEach
                results.add(
                    ExternalBodyFat(
                        time = rec.time,
                        bodyFatFraction = rec.percentage.value / 100.0,
                        clientRecordId = cid,
                        recordId = rec.metadata.id
                    )
                )
            }
            token = changes.nextChangesToken
            if (!changes.hasMore) break
        }
        return results to token
    }

    private fun tag(id: UUID): String = "$CLIENT_PREFIX${id}"

    private fun mealTypeFor(meal: com.apoorvdarshan.calorietracker.models.MealType): Int = when (meal) {
        com.apoorvdarshan.calorietracker.models.MealType.BREAKFAST -> HCMealType.MEAL_TYPE_BREAKFAST
        com.apoorvdarshan.calorietracker.models.MealType.LUNCH -> HCMealType.MEAL_TYPE_LUNCH
        com.apoorvdarshan.calorietracker.models.MealType.DINNER -> HCMealType.MEAL_TYPE_DINNER
        com.apoorvdarshan.calorietracker.models.MealType.SNACK -> HCMealType.MEAL_TYPE_SNACK
        com.apoorvdarshan.calorietracker.models.MealType.OTHER -> HCMealType.MEAL_TYPE_UNKNOWN
    }

    private fun mealTypeFrom(hcMealType: Int): com.apoorvdarshan.calorietracker.models.MealType = when (hcMealType) {
        HCMealType.MEAL_TYPE_BREAKFAST -> com.apoorvdarshan.calorietracker.models.MealType.BREAKFAST
        HCMealType.MEAL_TYPE_LUNCH -> com.apoorvdarshan.calorietracker.models.MealType.LUNCH
        HCMealType.MEAL_TYPE_DINNER -> com.apoorvdarshan.calorietracker.models.MealType.DINNER
        HCMealType.MEAL_TYPE_SNACK -> com.apoorvdarshan.calorietracker.models.MealType.SNACK
        else -> com.apoorvdarshan.calorietracker.models.MealType.OTHER
    }

    companion object {
        private const val TAG = "HealthConnectManager"
        private val lastLoggedAvailabilityKey = AtomicReference<String?>(null)
        private val writeVersions = HealthWriteVersions()

        const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        const val HEALTH_CONNECT_PLAY_STORE_WEB =
            "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE"

        private const val ACTION_MANAGE_HEALTH_PERMISSIONS =
            "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"
        private const val CLIENT_PREFIX = "fudai_"
        private const val WORKOUT_BURN_CLIENT_PREFIX = "fudai_workout_burn|"
        private const val WORKOUT_BURN_SEPARATOR = '|'
        private const val MAX_WORKOUT_BURN_CALORIES = 5_000

        /** Stable Health Connect identity containing the selected diary day. */
        fun workoutBurnClientRecordId(diaryDateKey: String, sessionId: UUID): String? {
            val canonicalDate = parseDiaryDateKey(diaryDateKey) ?: return null
            return "$WORKOUT_BURN_CLIENT_PREFIX$canonicalDate$WORKOUT_BURN_SEPARATOR$sessionId"
        }

        /** Parses and strictly validates an app-owned workout-burn client id. */
        fun parseWorkoutBurnClientRecordId(clientRecordId: String?): WorkoutBurnIdentity? {
            if (clientRecordId == null || !clientRecordId.startsWith(WORKOUT_BURN_CLIENT_PREFIX)) return null
            val components = clientRecordId
                .removePrefix(WORKOUT_BURN_CLIENT_PREFIX)
                .split(WORKOUT_BURN_SEPARATOR)
            if (components.size != 2) return null
            val date = parseDiaryDateKey(components[0]) ?: return null
            val sessionId = runCatching { UUID.fromString(components[1]) }.getOrNull() ?: return null
            return WorkoutBurnIdentity(sessionId = sessionId, diaryDateKey = date.toString())
        }

        private fun parseDiaryDateKey(value: String): LocalDate? =
            runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.toString() == value }

        /** Bump this when we add a new record type so users re-auth.
         *  v2 = added BodyFatRecord read+write permissions.
         *  v3 = added energy burn read permissions.
         *  v4 = added NutritionRecord read permission (food-log restore).
         *  v5 = added ActiveCaloriesBurnedRecord write permission (workout burn sync).
         *  v6 = added StepsRecord read permission (daily steps on Home). */
        const val CURRENT_TYPES_VERSION = 6
    }
}

internal fun externalActiveCalories(allActive: Double, ownActive: Double): Double =
    maxOf(0.0, allActive - ownActive)

/** Remove Fud AI's app-owned estimated active burn from a reported total as well. Basal is the
 *  non-active remainder of Health Connect's raw total; corrected total is basal + external active.
 *  This prevents a workout estimate written by this app from becoming evidence for its own goal. */
internal fun externalTotalCalories(rawTotal: Double?, allActive: Double, ownActive: Double): Double? {
    val reported = rawTotal?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val basal = maxOf(0.0, reported - maxOf(0.0, allActive))
    return basal + externalActiveCalories(allActive, ownActive)
}

private data class DailyEnergy(
    val active: Double,
    val total: Double?
)

data class HealthCapabilities(
    val weightRead: Boolean,
    val weightWrite: Boolean,
    val bodyFatRead: Boolean,
    val bodyFatWrite: Boolean,
    val nutritionRead: Boolean,
    val nutritionWrite: Boolean,
    val energyRead: Boolean,
    val activeEnergyWrite: Boolean,
    val stepsRead: Boolean
)

data class WorkoutBurnIdentity(
    val sessionId: UUID,
    val diaryDateKey: String
)

/** A validated active-energy estimate authored by this Fud AI package. */
data class HealthWorkoutBurn(
    val sessionId: UUID,
    val diaryDateKey: String,
    val caloriesBurned: Int,
    val healthSyncVersion: Int,
    val startTime: Instant,
    val endTime: Instant,
    val clientRecordId: String,
    val recordId: String
)

/** A NutritionRecord read back from Health Connect in Fud AI's own units —
 *  kcal for energy, grams/milligrams/micrograms per nutrient, matching
 *  [HealthConnectManager.writeNutrition]. */
data class ExternalNutrition(
    val time: Instant,
    val name: String?,
    val mealType: com.apoorvdarshan.calorietracker.models.MealType,
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val fiber: Double?,
    val sugar: Double?,
    val saturatedFat: Double?,
    val monounsaturatedFat: Double?,
    val polyunsaturatedFat: Double?,
    val transFat: Double?,
    val cholesterol: Double?,
    val caffeine: Double?,
    val sodium: Double?,
    val potassium: Double?,
    val calcium: Double?,
    val iron: Double?,
    val magnesium: Double?,
    val zinc: Double?,
    val vitaminA: Double?,
    val vitaminC: Double?,
    val vitaminD: Double?,
    val vitaminB12: Double?,
    val vitaminE: Double?,
    val vitaminK: Double?,
    val folate: Double?,
    val clientRecordId: String?,
    val recordId: String = "",
    val originPackage: String = ""
)

data class ExternalWeight(
    val time: Instant,
    val weightKg: Double,
    val clientRecordId: String?,
    /** Stable Health Connect record id (Metadata.id) — used as the dedup key when the
     *  source set no clientRecordId, so in-place value edits update rather than duplicate. */
    val recordId: String = ""
) {
    @Suppress("unused")
    val zoneOffset: ZoneOffset? get() = null
}

data class ExternalBodyFat(
    val time: Instant,
    /** 0–1 fraction, matching UserProfile.bodyFatPercentage convention. */
    val bodyFatFraction: Double,
    val clientRecordId: String?,
    /** Stable Health Connect record id (Metadata.id) — see [ExternalWeight.recordId]. */
    val recordId: String = ""
)

data class HealthEnergySummary(
    val activeAverageCalories: Int,
    val basalAverageCalories: Int?,
    val totalAverageCalories: Int?,
    val daysUsed: Int,
    val requestedDays: Int
)

data class HealthDailyEnergy(
    val activeCalories: Int,
    val totalCalories: Int?
)

data class HealthDatedEnergy(
    val date: LocalDate,
    /** Active energy after subtracting Fud AI's own estimated-workout data origin. */
    val activeCalories: Int,
    /** Corrected total (basal + external active), when Health Connect reports a raw total. */
    val totalCalories: Int?
)
