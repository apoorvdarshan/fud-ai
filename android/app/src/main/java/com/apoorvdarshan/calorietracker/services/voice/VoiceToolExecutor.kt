package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.WaterEntry
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.models.WorkoutClarification
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.services.FoodDataCentralService
import com.apoorvdarshan.calorietracker.services.OpenFoodFactsService
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.services.ai.ToolResultLimiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Runs the assistant's tools against the app ([VoiceAppPorts]) and owns the session's
 * confirmation cards, undo cards and drafts (shared/voice-assistant/README.md, "Tool
 * conventions"):
 *
 * - A change that needs confirmation (`confirm: always`, or `setting` while Confirm before
 *   saving is on) and lacks `user_confirmed: true` changes nothing; it shows a card and
 *   answers `confirmation_required` with a summary. Tapping Confirm performs it and the
 *   caller tells the model through the next turn.
 * - Writes show an Undo card for eight seconds; deletes have none (they were confirmed).
 * - Drafts from photos, barcodes, descriptions and workouts live for the session and can
 *   be logged from their card.
 *
 * All calls are expected on one thread (the controller's main scope).
 */
class VoiceToolExecutor(
    private val ports: VoiceAppPorts,
    catalog: List<VoiceToolSpec>,
    var context: VoiceSessionContext,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Asks the controller to end the session once the goodbye has been spoken. */
    var onEndConversation: () -> Unit = {}

    private val specs: Map<String, VoiceToolSpec> =
        (catalog + VoiceToolCatalog.coachDataTools()).associateBy { it.name }

    private val drafts = DraftStore()
    private val undo = UndoRegistry(clock)
    private val pending = LinkedHashMap<String, PendingConfirmation>()
    private val completed = LinkedHashMap<String, Completed>()
    // Model calls and card taps can interleave while a repository write suspends.
    // Serialize writes only; a photo picker must not prevent close_camera/navigation.
    private val mutationMutex = Mutex()
    private var nextCard = 1

    private val _cards = MutableStateFlow<List<VoiceCard>>(emptyList())
    val cards: StateFlow<List<VoiceCard>> = _cards.asStateFlow()

    private class Performed(val result: JsonObject, val undo: (suspend () -> Unit)? = null)

    private class PendingConfirmation(
        val toolName: String,
        val signature: String,
        val change: VoiceChange,
        val effect: ToolEffect,
        val perform: suspend () -> Performed
    )

    private class Completed(val result: JsonObject, val change: VoiceChange, val expiresAtMillis: Long)

    private val handlers: Map<String, suspend (ToolArgs, VoiceToolSpec) -> JsonObject> = mapOf(
        "get_app_state" to { _, _ -> appState() },
        "open_screen" to { args, _ -> openScreen(args) },
        "set_diary_date" to { args, _ -> setDiaryDate(args) },
        "open_food_entry" to { args, _ -> openFoodEntry(args) },
        "open_camera" to { args, _ -> openCamera(args) },
        "capture_photo" to { args, _ -> capturePhoto(args) },
        "close_camera" to { _, _ -> closeCamera() },
        "pick_photos" to { args, _ -> pickPhotos(args) },
        "scan_barcode" to { _, _ -> scanBarcode() },
        "describe_food" to { args, _ -> describeFood(args) },
        "search_foods" to { args, _ -> searchFoods(args) },
        "log_food" to ::logFood,
        "update_food_entry" to ::updateFoodEntry,
        "delete_food_entry" to ::deleteFoodEntry,
        "log_water" to ::logWater,
        "start_fast" to ::startFast,
        "end_fast" to ::endFast,
        "log_weight" to ::logWeight,
        "log_body_fat" to ::logBodyFat,
        "describe_workout" to { args, _ -> describeWorkout(args) },
        "log_workout" to ::logWorkout,
        "log_cardio" to ::logCardio,
        "get_settings" to { _, _ -> getSettings() },
        "update_setting" to ::updateSetting,
        "end_conversation" to { _, _ -> endConversation() }
    )

    /** Every tool this executor can run: catalog actions plus Coach's data tools. */
    val handledToolNames: Set<String> get() = handlers.keys + VoiceToolCatalog.coachDataTools().map { it.name }

    suspend fun execute(call: LiveToolCall): JsonObject {
        val effect = specs[call.name]?.effect
        return if (effect == ToolEffect.WRITE || effect == ToolEffect.DESTRUCTIVE) {
            mutationMutex.withLock { executeCall(call) }
        } else executeCall(call)
    }

    private suspend fun executeCall(call: LiveToolCall): JsonObject {
        val spec = specs[call.name]
            ?: return ToolResults.error(ToolError.UNSUPPORTED, "Unknown tool: ${call.name}.")
        if (spec.workout && !(context.workoutDiaryEnabled && ports.workoutDiaryEnabled())) {
            return ToolResults.error(ToolError.WORKOUT_DIARY_OFF, "The workout diary is turned off.")
        }
        val args = ToolArgs(call.arguments)
        return try {
            if (spec.isCoachDataTool) {
                coachData(spec.name, args)
            } else {
                val handler = handlers[spec.name]
                    ?: return ToolResults.error(ToolError.UNSUPPORTED, "This action isn't available on Android.")
                handler(args, spec)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            failure.result
        } catch (error: Throwable) {
            VoiceLog.warn(TAG, "Tool ${call.name} failed", error)
            ToolResults.error(ToolError.UNSUPPORTED, "That didn't work. Please try again.")
        }
    }

    /** Clears drafts and cards when a session ends. */
    fun reset() {
        drafts.clear()
        undo.clear()
        pending.clear()
        completed.clear()
        _cards.value = emptyList()
    }

    // MARK: - Cards

    /** The user tapped Confirm. Performs the change and returns a note for the model. */
    suspend fun confirmCard(cardId: String): String? = mutationMutex.withLock { confirmCardLocked(cardId) }

    private suspend fun confirmCardLocked(cardId: String): String? {
        val confirmation = pending.remove(cardId) ?: return null
        removeCard(cardId)
        val result = try {
            run(confirmation.effect, confirmation.change, confirmation.perform)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            failure.result
        } catch (error: Throwable) {
            VoiceLog.warn(TAG, "Confirmed change failed", error)
            ToolResults.error(ToolError.UNSUPPORTED, "That change could not be saved. Please try again.")
        }
        pruneCompleted()
        if (ToolResults.isOk(result)) {
            completed[confirmation.signature] = Completed(result, confirmation.change, clock() + COMPLETED_MEMORY_MILLIS)
        }
        return if (ToolResults.isOk(result)) {
            "$APP_NOTE The user tapped Confirm on the card to ${confirmation.change.modelSummary}. It is done; " +
                "do not call ${confirmation.toolName} again for it. Result: $result"
        } else {
            "$APP_NOTE The user tapped Confirm on the card to ${confirmation.change.modelSummary}, but it failed: $result"
        }
    }

    /** The user tapped Cancel on a confirmation card. */
    fun cancelCard(cardId: String): String? {
        val confirmation = pending.remove(cardId) ?: return null
        removeCard(cardId)
        return "$APP_NOTE The user tapped Cancel on the card to ${confirmation.change.modelSummary}. Nothing changed."
    }

    /** The user tapped Undo within its window. */
    suspend fun undoCard(cardId: String): String? = mutationMutex.withLock { undoCardLocked(cardId) }

    private suspend fun undoCardLocked(cardId: String): String? {
        val entry = undo.take(cardId)
        removeCard(cardId)
        entry ?: return null
        return try {
            entry.restore()
            completed.entries.removeAll { it.value.change == entry.change }
            "$APP_NOTE The user tapped Undo, so this was reverted: ${entry.change.modelSummary}."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            VoiceLog.warn(TAG, "Undo failed", error)
            "$APP_NOTE Undo failed for ${entry.change.modelSummary}. Do not say it was reverted."
        }
    }

    /** The user tapped Log on a draft card (the tap is their consent). */
    suspend fun logDraftFromCard(cardId: String): String? = mutationMutex.withLock { logDraftFromCardLocked(cardId) }

    private suspend fun logDraftFromCardLocked(cardId: String): String? {
        val card = _cards.value.firstOrNull { it.id == cardId } as? VoiceCard.Draft ?: return null
        val result = try {
            when (val draft = drafts.get(card.draftId)) {
                is VoiceDraft.Food -> {
                    val prepared = prepareDraftFood(draft, servings = 1.0)
                    val timestamp = timestampFor(ports.diaryDate(), null)
                    val meal = ports.mealForTime(localTime(timestamp))
                    run(ToolEffect.WRITE, prepared.change(meal, localDate(timestamp))) {
                        performFoodLog(prepared, timestamp, meal, draft)
                    }
                }
                is VoiceDraft.Workout -> {
                    val date = LocalDate.parse(draft.draft.date)
                    run(ToolEffect.WRITE, VoiceChange.LogWorkout(draft.draft.exercises.size, date)) {
                        performWorkoutLog(draft)
                    }
                }
                null -> return null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            failure.result
        } catch (error: Throwable) {
            VoiceLog.warn(TAG, "Draft could not be logged", error)
            ToolResults.error(ToolError.UNSUPPORTED, "That draft could not be saved. Please try again.")
        }
        return if (ToolResults.isOk(result)) {
            "$APP_NOTE The user tapped Log on the draft card ${card.draftId}. It is logged; do not log it again. Result: $result"
        } else {
            "$APP_NOTE The user tapped Log on the draft card ${card.draftId}, but it failed: $result"
        }
    }

    fun dismissCard(cardId: String) {
        pending.remove(cardId)
        removeCard(cardId)
    }

    /** Removes Undo cards whose eight seconds are over. */
    fun expireUndoCards() {
        val expired = undo.expire().toSet()
        val now = clock()
        val stale = _cards.value.filter {
            it is VoiceCard.Undo && (it.id in expired || now >= it.expiresAtMillis)
        }.map { it.id }.toSet()
        if (stale.isNotEmpty()) _cards.value = _cards.value.filterNot { it.id in stale }
    }

    /** A confirmation card is waiting for the user (the idle timeout pauses meanwhile). */
    val hasPendingConfirmation: Boolean get() = pending.isNotEmpty()

    private fun addCard(card: VoiceCard) {
        val cards = (_cards.value.filterNot { it.id == card.id } + card).toMutableList()
        // Keep the overlay readable: drop the oldest non-confirmation cards first.
        while (cards.size > MAX_CARDS) {
            val index = cards.indexOfFirst { it !is VoiceCard.Confirmation }.takeIf { it >= 0 } ?: 0
            val removed = cards.removeAt(index)
            pending.remove(removed.id)
        }
        _cards.value = cards
    }

    private fun removeCard(cardId: String) {
        _cards.value = _cards.value.filterNot { it.id == cardId }
    }

    private fun replaceCard(card: VoiceCard) {
        _cards.value = _cards.value.map { if (it.id == card.id) card else it }
    }

    private fun nextCardId(): String = "card_${nextCard++}"

    // MARK: - Confirmation and undo

    private suspend fun confirmable(
        spec: VoiceToolSpec,
        args: ToolArgs,
        change: VoiceChange,
        perform: suspend () -> Performed
    ): JsonObject {
        val signature = args.signature(spec.name)
        pruneCompleted()
        if (args.userConfirmed) {
            // The user already confirmed this exact change on its card.
            completed[signature]?.let { done ->
                return JsonObject(done.result + ("already_done" to JsonPrimitive(true)))
            }
        }
        if (ConfirmationPolicy.needsConfirmation(spec.confirm, context.confirmSaves, args.userConfirmed)) {
            dropPending { it.signature == signature }
            val cardId = nextCardId()
            pending[cardId] = PendingConfirmation(spec.name, signature, change, spec.effect, perform)
            addCard(VoiceCard.Confirmation(cardId, change, destructive = spec.effect == ToolEffect.DESTRUCTIVE))
            return ToolResults.error(
                ToolError.CONFIRMATION_REQUIRED,
                "Nothing changed yet. Ask the user to confirm, then call again with user_confirmed: true."
            ) { put("summary", change.modelSummary) }
        }
        // Other pending changes using the same tool still need their own consent.
        dropPending { it.signature == signature }
        return run(spec.effect, change, perform)
    }

    private suspend fun run(effect: ToolEffect, change: VoiceChange, perform: suspend () -> Performed): JsonObject {
        val performed = perform()
        val restore = performed.undo
        if (restore == null || effect != ToolEffect.WRITE) return performed.result
        val entry = undo.register(change, restore)
        addCard(VoiceCard.Undo(entry.id, change, entry.expiresAtMillis, undo.windowMillis))
        return JsonObject(performed.result + ("undo_available" to JsonPrimitive(true)))
    }

    private fun dropPending(predicate: (PendingConfirmation) -> Boolean) {
        val ids = pending.filterValues(predicate).keys.toList()
        ids.forEach {
            pending.remove(it)
            removeCard(it)
        }
    }

    private fun pruneCompleted() {
        val now = clock()
        completed.entries.removeAll { now >= it.value.expiresAtMillis }
    }

    // MARK: - Context and navigation

    private suspend fun appState(): JsonObject {
        val screen = ports.screenState()
        val prefs = ports.trackingPrefs()
        val profile = ports.profile()
        val today = ports.today()
        val diaryDate = ports.diaryDate()
        val entries = ports.foodEntries()
        val water = if (prefs.waterEnabled) ports.waterEntries() else emptyList()
        val fast = ports.activeFast()
        val cameraMode = ports.cameraMode()

        fun totals(date: LocalDate): JsonObject {
            val day = entries.filter { localDate(it.timestamp) == date }
            return buildJsonObject {
                put("date", VoiceArguments.formatDate(date))
                put("entries", day.size)
                put("calories", day.sumOf { it.calories })
                put("protein_g", round1(day.sumOf { it.protein }))
                put("carbs_g", round1(day.sumOf { it.carbs }))
                put("fat_g", round1(day.sumOf { it.fat }))
                if (prefs.waterEnabled) {
                    put("water_ml", water.filter { localDate(it.date) == date }.sumOf { it.milliliters })
                }
            }
        }

        return ToolResults.ok {
            put("screen", screen.screen?.wireValue ?: "other")
            screen.section?.let { put("settings_section", it.wireValue) }
            // open_screen can't show these until the user turns them back on.
            val hidden = ports.hiddenScreens()
            if (hidden.isNotEmpty()) {
                putJsonArray("hidden_screens") {
                    VoiceScreen.entries.filter { it in hidden }.forEach { add(JsonPrimitive(it.wireValue)) }
                }
            }
            put("diary_date", VoiceArguments.formatDate(diaryDate))
            put("today", VoiceArguments.formatDate(today))
            put("time", VoiceArguments.formatTime(localTime(ports.now())))
            putJsonObject("camera") {
                put("open", cameraMode != null)
                cameraMode?.let { put("mode", it.wireValue) }
            }
            put("today_totals", totals(today))
            if (diaryDate != today) put("diary_date_totals", totals(diaryDate))
            if (profile != null) {
                putJsonObject("goals") {
                    put("calories", profile.effectiveCalories)
                    put("protein_g", profile.effectiveProtein)
                    put("carbs_g", profile.effectiveCarbs)
                    put("fat_g", profile.effectiveFat)
                    if (prefs.waterEnabled) put("water_ml", prefs.waterGoalMl)
                }
            }
            fast?.let { session ->
                putJsonObject("active_fast") {
                    put("started_at", session.startedAt.toString())
                    put("elapsed_minutes", session.durationSeconds(ports.now()) / 60)
                    put("goal_hours", session.goalMinutes / 60.0)
                }
            }
            put("unit_system", if (prefs.weightMetric) "metric" else "imperial")
            putJsonObject("session") {
                put("provider", context.provider.storageValue)
                put("camera_frames", context.cameraFrames.wireValue)
                put("auto_capture", context.autoCapture)
                put("confirm_saves", context.confirmSaves)
                put("workout_diary", context.workoutDiaryEnabled && ports.workoutDiaryEnabled())
                put("water_tracking", prefs.waterEnabled)
                put("fasting_tracking", prefs.fastingEnabled)
            }
        }
    }

    private fun openScreen(args: ToolArgs): JsonObject {
        val screen = VoiceScreen.fromWire(args.requireString("screen"))
            ?: fail(ToolError.INVALID_ARGUMENTS, "screen must be one of home, progress, coach, workouts, settings.")
        val section = args.string("settings_section")?.let { raw ->
            VoiceSettingsSection.fromWire(raw)
                ?: fail(ToolError.INVALID_ARGUMENTS, "Unknown settings_section: $raw.")
        }
        if (section != null && screen != VoiceScreen.SETTINGS) {
            fail(ToolError.INVALID_ARGUMENTS, "settings_section only works with screen=settings.")
        }
        if (screen in ports.hiddenScreens()) {
            fail(
                ToolError.UNSUPPORTED,
                "The user hid the ${screen.wireValue} tab. It can be shown again in Settings > App Settings > Tab Bar."
            )
        }
        ports.showScreen(screen, section)
        return ToolResults.ok {
            put("screen", screen.wireValue)
            section?.let { put("settings_section", it.wireValue) }
        }
    }

    private fun setDiaryDate(args: ToolArgs): JsonObject {
        val date = parseDateArg(args.requireString("date"))
        ports.showDiaryDate(date)
        return ToolResults.ok { put("diary_date", VoiceArguments.formatDate(date)) }
    }

    private suspend fun openFoodEntry(args: ToolArgs): JsonObject {
        val entry = findEntry(args.requireString("entry_id"))
        ports.showFoodEntry(entry)
        return ToolResults.ok {
            put("entry_id", entry.id.toString())
            put("name", entry.name)
            put("date", VoiceArguments.formatDate(localDate(entry.timestamp)))
        }
    }

    // MARK: - Camera, photos and barcodes

    private suspend fun openCamera(args: ToolArgs): JsonObject {
        val mode = args.string("mode")?.lowercase(Locale.ROOT) ?: VoiceCameraMode.FOOD.wireValue
        if (mode != VoiceCameraMode.FOOD.wireValue) {
            fail(ToolError.INVALID_ARGUMENTS, "Only mode=food opens here; scan_barcode opens the barcode camera.")
        }
        return when (ports.openCamera(VoiceCameraMode.FOOD)) {
            CameraOutcome.Opened -> ToolResults.ok {
                put("camera", "open")
                put("mode", VoiceCameraMode.FOOD.wireValue)
                put("camera_frames", context.cameraFrames.wireValue)
                put("auto_capture", context.autoCapture)
            }
            CameraOutcome.PermissionDenied ->
                ToolResults.error(ToolError.PERMISSION_DENIED, "The user did not allow camera access.")
            CameraOutcome.Unavailable ->
                ToolResults.error(ToolError.CAMERA_UNAVAILABLE, "The camera could not be opened.")
            CameraOutcome.TimedOut ->
                ToolResults.error(ToolError.TIMEOUT, "The camera did not open in time.")
        }
    }

    private suspend fun capturePhoto(args: ToolArgs): JsonObject {
        if (ports.cameraMode() != VoiceCameraMode.FOOD) {
            fail(ToolError.CAMERA_UNAVAILABLE, "The camera isn't open. Call open_camera first.")
        }
        val note = args.string("note")?.take(MAX_NOTE_CHARS)
        val photo = when (val outcome = ports.takePhoto()) {
            is PhotoOutcome.Taken -> outcome.jpeg
            PhotoOutcome.NotOpen -> fail(ToolError.CAMERA_UNAVAILABLE, "The camera isn't open. Call open_camera first.")
            PhotoOutcome.Failed -> fail(ToolError.CAMERA_UNAVAILABLE, "The photo could not be taken.")
        }
        val analysis = analyzing { ports.analyzeFoodPhotos(listOf(photo), note) }.copy(customNote = note)
        return foodDraft(analysis, listOf(photo), FoodSource.SNAP_FOOD) { put("camera", "closed") }
    }

    private fun closeCamera(): JsonObject {
        ports.closeCamera()
        return ToolResults.ok { put("camera", "closed") }
    }

    private suspend fun pickPhotos(args: ToolArgs): JsonObject {
        val note = args.string("note")?.take(MAX_NOTE_CHARS)
        val maxPhotos = ports.maxFoodPhotos().coerceIn(1, MAX_PICKED_PHOTOS)
        val images = when (val outcome = ports.pickPhotos(maxPhotos)) {
            is PickOutcome.Picked -> outcome.images.take(maxPhotos)
            PickOutcome.Cancelled -> return ToolResults.error(ToolError.CANCELLED, "The user closed the photo picker.")
            PickOutcome.TimedOut -> return ToolResults.error(ToolError.TIMEOUT, "No photos were chosen in time.")
        }
        if (images.isEmpty()) return ToolResults.error(ToolError.CANCELLED, "No photos were chosen.")
        val analysis = analyzing { ports.analyzeFoodPhotos(images, note) }.copy(customNote = note)
        return foodDraft(analysis, images, FoodSource.SNAP_FOOD) { put("photos", images.size) }
    }

    private suspend fun scanBarcode(): JsonObject {
        val code = when (val outcome = ports.scanBarcode()) {
            is BarcodeOutcome.Scanned -> outcome.code
            BarcodeOutcome.Cancelled -> return ToolResults.error(ToolError.CANCELLED, "The user closed the scanner.")
            BarcodeOutcome.TimedOut -> return ToolResults.error(ToolError.TIMEOUT, "No barcode was found in time.")
            BarcodeOutcome.PermissionDenied ->
                return ToolResults.error(ToolError.PERMISSION_DENIED, "The user did not allow camera access.")
            BarcodeOutcome.Unavailable ->
                return ToolResults.error(ToolError.CAMERA_UNAVAILABLE, "The camera could not be opened.")
        }
        val product = try {
            ports.lookupBarcode(code)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (lookup: OpenFoodFactsService.LookupException) {
            return when (lookup.failure) {
                OpenFoodFactsService.LookupFailure.PRODUCT_NOT_FOUND ->
                    ToolResults.error(ToolError.NOT_FOUND, "No product was found for barcode $code. Offer to take a photo of the label instead.")
                OpenFoodFactsService.LookupFailure.MISSING_NUTRITION ->
                    ToolResults.error(ToolError.NOT_FOUND, "The product for barcode $code has no nutrition data. Offer to photograph the label.")
                OpenFoodFactsService.LookupFailure.INVALID_BARCODE ->
                    ToolResults.error(ToolError.INVALID_ARGUMENTS, "That barcode could not be read. Try scanning again.")
                else -> ToolResults.error(ToolError.ANALYSIS_FAILED, "The barcode lookup failed. Check the connection and try again.")
            }
        } catch (usda: FoodDataCentralService.LookupException) {
            return ToolResults.error(
                ToolError.NOT_FOUND,
                "No product was found for barcode $code in Open Food Facts, and USDA FoodData Central rejected the API key saved in Settings > Product information. Offer to take a photo of the label instead."
            )
        } catch (error: Throwable) {
            return ToolResults.error(ToolError.ANALYSIS_FAILED, "The barcode lookup failed. Please try again.")
        }
        return foodDraft(product.analysis, listOfNotNull(product.imageBytes), product.source) {
            put("barcode", code)
        }
    }

    private suspend fun describeFood(args: ToolArgs): JsonObject {
        val description = args.requireString("description").take(MAX_DESCRIPTION_CHARS)
        val analysis = analyzing { ports.analyzeFoodText(description) }
        return foodDraft(analysis, emptyList(), FoodSource.TEXT_INPUT)
    }

    private suspend fun <T> analyzing(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ToolFailure) {
        throw failure
    } catch (error: AiError) {
        if (error.kind == AiErrorKind.HOSTED_QUOTA_EXCEEDED) {
            fail(ToolError.QUOTA_EXCEEDED, error.kind.defaultMessage)
        } else {
            fail(ToolError.ANALYSIS_FAILED, error.kind.defaultMessage)
        }
    } catch (error: Throwable) {
        fail(ToolError.ANALYSIS_FAILED, "The food analysis failed. Please try again.")
    }

    private fun foodDraft(
        analysis: FoodAnalysis,
        images: List<ByteArray>,
        source: FoodSource,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}
    ): JsonObject {
        val id = drafts.nextId()
        drafts.add(VoiceDraft.Food(id, analysis, images, source))
        val items = draftItems(analysis)
        val totals = DraftTotals(analysis.calories, analysis.protein, analysis.carbs, analysis.fat)
        addCard(VoiceCard.Draft(nextCardId(), id, isWorkout = false, title = analysis.name, items = items, totals = totals))
        return ToolResults.ok {
            put("draft_id", id)
            put("name", analysis.name)
            putJsonArray("items") { items.forEach { add(draftItemJson(it)) } }
            putJsonObject("totals") {
                put("calories", totals.calories)
                put("protein_g", round1(totals.proteinG))
                put("carbs_g", round1(totals.carbsG))
                put("fat_g", round1(totals.fatG))
            }
            put("logged", false)
            extra()
        }
    }

    private fun draftItemJson(item: DraftItem): JsonObject = buildJsonObject {
        put("name", item.name)
        item.serving?.let { put("serving", it) }
        item.calories?.let { put("calories", it) }
        item.proteinG?.let { put("protein_g", round1(it)) }
        item.carbsG?.let { put("carbs_g", round1(it)) }
        item.fatG?.let { put("fat_g", round1(it)) }
        item.detail?.let { put("detail", it) }
    }

    // MARK: - Saved foods and logging

    private suspend fun searchFoods(args: ToolArgs): JsonObject {
        val query = args.requireString("query").lowercase(Locale.ROOT)
        val limit = (args.number("limit")?.roundToInt() ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        val tokens = query.split(Regex("\\s+")).filter { it.length > 1 }
        val kindOrder = listOf(SavedFoodKind.FAVORITE, SavedFoodKind.FREQUENT, SavedFoodKind.RECENT)
        val matches = ports.savedFoods()
            .mapNotNull { saved ->
                val name = saved.entry.name.lowercase(Locale.ROOT)
                val score = when {
                    name == query -> 3
                    name.contains(query) -> 2
                    tokens.isNotEmpty() && tokens.all { name.contains(it) } -> 1
                    else -> 0
                }
                if (score == 0) null else saved to score
            }
            .sortedWith(
                compareByDescending<Pair<SavedFood, Int>> { it.second }
                    .thenBy { kindOrder.indexOf(it.first.kind) }
                    .thenByDescending { it.first.timesLogged ?: 0 }
                    .thenByDescending { it.first.entry.timestamp }
            )
            .map { it.first }
            .distinctBy { it.savedId }
            .take(limit)
        return ToolResults.ok {
            put("query", query)
            put("count", matches.size)
            putJsonArray("results") {
                matches.forEach { saved ->
                    add(buildJsonObject {
                        put("saved_food_id", saved.savedId)
                        put("name", saved.entry.name)
                        put("kind", saved.kind.wireValue)
                        put("calories", saved.entry.calories)
                        put("protein_g", round1(saved.entry.protein))
                        put("carbs_g", round1(saved.entry.carbs))
                        put("fat_g", round1(saved.entry.fat))
                        servingText(saved.entry)?.let { put("serving", it) }
                        saved.timesLogged?.let { put("times_logged", it) }
                        put("last_logged", VoiceArguments.formatDate(localDate(saved.entry.timestamp)))
                    })
                }
            }
        }
    }

    /** A food ready to log: name, nutrition and how to build the entry. */
    private class PreparedFood(
        val name: String,
        val calories: Int,
        val discard: suspend (FoodEntry) -> Unit = {},
        val build: suspend (UUID, Instant, MealType) -> FoodEntry
    ) {
        fun change(meal: MealType, date: LocalDate) = VoiceChange.LogFood(name, calories, meal, date)
    }

    private suspend fun logFood(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val draftId = args.string("draft_id")
        val savedId = args.string("saved_food_id")
        val item = args.obj("item")
        if (listOfNotNull(draftId, savedId, item).size != 1) {
            fail(ToolError.INVALID_ARGUMENTS, "Give exactly one of draft_id, saved_food_id or item.")
        }
        val servings = args.number("servings") ?: 1.0
        if (servings <= 0 || servings > MAX_SERVINGS) {
            fail(ToolError.INVALID_ARGUMENTS, "servings must be more than 0 and at most $MAX_SERVINGS.")
        }
        val date = args.string("date")?.let(::parseDateArg) ?: ports.diaryDate()
        val time = args.string("time")?.let(::parseTimeArg)
        val timestamp = timestampFor(date, time)
        val meal = args.string("meal")?.let { raw ->
            VoiceArguments.parseMeal(raw)
                ?: fail(ToolError.INVALID_ARGUMENTS, "meal must be breakfast, lunch, dinner or snack.")
        } ?: ports.mealForTime(localTime(timestamp))

        var foodDraft: VoiceDraft.Food? = null
        val prepared = when {
            draftId != null -> {
                val draft = drafts.get(draftId)
                    ?: fail(ToolError.NOT_FOUND, "No draft $draftId in this conversation.")
                if (draft !is VoiceDraft.Food) fail(ToolError.INVALID_ARGUMENTS, "$draftId is a workout draft; use log_workout.")
                foodDraft = draft
                prepareDraftFood(draft, servings)
            }
            savedId != null -> prepareSavedFood(savedId, servings)
            else -> prepareManualFood(ToolArgs(item!!), servings)
        }
        val change = prepared.change(meal, date)
        return confirmable(spec, args, change) { performFoodLog(prepared, timestamp, meal, foodDraft) }
    }

    private suspend fun performFoodLog(
        prepared: PreparedFood,
        timestamp: Instant,
        meal: MealType,
        draft: VoiceDraft.Food?
    ): Performed {
        if (draft != null && draft.loggedEntryIds.isNotEmpty()) {
            fail(ToolError.INVALID_ARGUMENTS, "${draft.id} is already logged. Use update_food_entry to change it.")
        }
        if (ports.cameraMode() == VoiceCameraMode.FOOD) ports.closeCamera()
        val entry = prepared.build(UUID.randomUUID(), timestamp, meal)
        var adopted = false
        try {
            if (!ports.addFoodEntry(entry)) {
                fail(ToolError.UNSUPPORTED, "Food logging is paused while a fast is running. Offer to end the fast first.")
            }
            adopted = true
        } finally {
            if (!adopted) withContext(NonCancellable) { prepared.discard(entry) }
        }
        draft?.let { food ->
            food.loggedEntryIds = food.loggedEntryIds + entry.id.toString()
            markDraftCardLogged(food.id, true)
        }
        return Performed(
            ToolResults.ok {
                putJsonArray("entry_ids") { add(JsonPrimitive(entry.id.toString())) }
                put("name", entry.name)
                put("calories", entry.calories)
                put("protein_g", round1(entry.protein))
                put("carbs_g", round1(entry.carbs))
                put("fat_g", round1(entry.fat))
                put("meal", VoiceArguments.mealWireValue(entry.mealType))
                put("date", VoiceArguments.formatDate(localDate(entry.timestamp)))
                put("time", VoiceArguments.formatTime(localTime(entry.timestamp)))
            },
            undo = {
                requireUnchangedFood(entry)
                ports.deleteFoodEntry(entry.id)
                draft?.let { food ->
                    food.loggedEntryIds = food.loggedEntryIds - entry.id.toString()
                    if (food.loggedEntryIds.isEmpty()) markDraftCardLogged(food.id, false)
                }
            }
        )
    }

    private fun markDraftCardLogged(draftId: String, logged: Boolean) {
        _cards.value.filterIsInstance<VoiceCard.Draft>()
            .filter { it.draftId == draftId }
            .forEach { replaceCard(it.copy(logged = logged)) }
    }

    private fun prepareDraftFood(draft: VoiceDraft.Food, servings: Double): PreparedFood {
        val analysis = draft.analysis
        return PreparedFood(analysis.name, (analysis.calories * servings).roundToInt(),
            discard = { ports.discardFoodImages(it.allImageFilenames) }
        ) { id, timestamp, meal ->
            val filenames = if (draft.images.isEmpty()) emptyList() else ports.storeFoodImages(draft.images)
            foodEntryFromAnalysis(analysis, id, timestamp, meal, servings, draft.source, filenames)
        }
    }

    private suspend fun prepareSavedFood(savedId: String, servings: Double): PreparedFood {
        val template = findSavedFood(savedId)
        return PreparedFood(template.name, (template.calories * servings).roundToInt()) { id, timestamp, meal ->
            if (findSavedFood(savedId) != template) {
                fail(ToolError.INVALID_ARGUMENTS, "That saved food changed. Search again before logging it.")
            }
            template.duplicatedForLogging(timestamp, meal).scaledBy(servings).copy(id = id)
        }
    }

    private suspend fun findSavedFood(savedId: String): FoodEntry {
        val entry = when {
            savedId.startsWith("favorite-") -> ports.savedFoods().firstOrNull {
                it.kind == SavedFoodKind.FAVORITE && it.savedId == savedId
            }?.entry
            savedId.startsWith("food-") -> ports.foodEntries().firstOrNull {
                it.id.toString() == savedId.removePrefix("food-")
            }
            // Accept an unprefixed id from older conversation context, but never a cached copy.
            else -> ports.foodEntries().firstOrNull { it.id.toString() == savedId }
                ?: ports.savedFoods().firstOrNull { it.entry.id.toString() == savedId }?.entry
        }
        return entry ?: fail(ToolError.NOT_FOUND, "No saved food $savedId. Call search_foods first.")
    }

    private fun prepareManualFood(item: ToolArgs, servings: Double): PreparedFood {
        val name = item.requireString("name").take(MAX_NAME_CHARS)
        val calories = item.requireNumber("calories")
        if (calories < 0 || calories > MAX_CALORIES) fail(ToolError.INVALID_ARGUMENTS, "calories must be 0 to $MAX_CALORIES.")
        val protein = macro(item, "protein_g")
        val carbs = macro(item, "carbs_g")
        val fat = macro(item, "fat_g")
        val serving = item.string("serving")?.take(MAX_NAME_CHARS)
        val total = (calories * servings).roundToInt()
        return PreparedFood(name, total) { id, timestamp, meal ->
            FoodEntry(
                id = id,
                name = name,
                calories = total,
                protein = protein * servings,
                carbs = carbs * servings,
                fat = fat * servings,
                timestamp = timestamp,
                source = FoodSource.MANUAL,
                mealType = meal,
                servingSizeGrams = serving?.let(VoiceArguments::gramsIn)?.times(servings),
                customNote = serving?.let { if (VoiceArguments.gramsIn(it) == null) it else null }
            )
        }
    }

    private fun macro(args: ToolArgs, name: String): Double {
        val value = args.number(name) ?: return 0.0
        if (value < 0 || value > MAX_MACRO_G) fail(ToolError.INVALID_ARGUMENTS, "$name must be 0 to $MAX_MACRO_G.")
        return value
    }

    private suspend fun updateFoodEntry(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val entry = findEntry(args.requireString("entry_id"))
        var updated = entry
        args.number("servings")?.let { servings ->
            if (servings <= 0 || servings > MAX_SERVINGS) {
                fail(ToolError.INVALID_ARGUMENTS, "servings must be more than 0 and at most $MAX_SERVINGS.")
            }
            updated = updated.scaledBy(servings)
        }
        args.string("name")?.let { updated = updated.copy(name = it.take(MAX_NAME_CHARS)) }
        args.number("calories")?.let {
            if (it < 0 || it > MAX_CALORIES) fail(ToolError.INVALID_ARGUMENTS, "calories must be 0 to $MAX_CALORIES.")
            updated = updated.copy(calories = it.roundToInt())
        }
        if (args.raw.containsKey("protein_g")) updated = updated.copy(protein = macro(args, "protein_g"))
        if (args.raw.containsKey("carbs_g")) updated = updated.copy(carbs = macro(args, "carbs_g"))
        if (args.raw.containsKey("fat_g")) updated = updated.copy(fat = macro(args, "fat_g"))
        args.string("meal")?.let { raw ->
            updated = updated.copy(
                mealType = VoiceArguments.parseMeal(raw)
                    ?: fail(ToolError.INVALID_ARGUMENTS, "meal must be breakfast, lunch, dinner or snack.")
            )
        }
        val newDate = args.string("date")?.let(::parseDateArg)
        val newTime = args.string("time")?.let(::parseTimeArg)
        if (newDate != null || newTime != null) {
            val zoned = entry.timestamp.atZone(ports.zone())
            val date = newDate ?: zoned.toLocalDate()
            val time = newTime ?: zoned.toLocalTime()
            updated = updated.copy(timestamp = date.atTime(time).atZone(ports.zone()).toInstant())
        }
        if (updated == entry) fail(ToolError.INVALID_ARGUMENTS, "Nothing to change. Give at least one field.")
        val final = updated
        return confirmable(spec, args, VoiceChange.UpdateFood(final.name, final.calories)) {
            requireUnchangedFood(entry)
            ports.updateFoodEntry(final)
            Performed(
                ToolResults.ok {
                    put("entry_id", final.id.toString())
                    put("name", final.name)
                    put("calories", final.calories)
                    put("protein_g", round1(final.protein))
                    put("carbs_g", round1(final.carbs))
                    put("fat_g", round1(final.fat))
                    put("meal", VoiceArguments.mealWireValue(final.mealType))
                    put("date", VoiceArguments.formatDate(localDate(final.timestamp)))
                    put("time", VoiceArguments.formatTime(localTime(final.timestamp)))
                },
                undo = {
                    requireUnchangedFood(final)
                    ports.updateFoodEntry(entry)
                }
            )
        }
    }

    private suspend fun deleteFoodEntry(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val entry = findEntry(args.requireString("entry_id"))
        return confirmable(spec, args, VoiceChange.DeleteFood(entry.name, entry.calories)) {
            requireUnchangedFood(entry)
            ports.deleteFoodEntry(entry.id)
            Performed(ToolResults.ok {
                put("deleted_entry_id", entry.id.toString())
                put("name", entry.name)
            })
        }
    }

    /** A confirmation card must not overwrite or delete a later edit made in the diary. */
    private suspend fun requireUnchangedFood(expected: FoodEntry) {
        val current = ports.foodEntries().firstOrNull { it.id == expected.id }
            ?: fail(ToolError.NOT_FOUND, "That food entry no longer exists.")
        if (current != expected) fail(ToolError.INVALID_ARGUMENTS, "That food entry changed. Read it again before changing it.")
    }

    // MARK: - Water, fasting and body

    private suspend fun logWater(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val amount = args.requireNumber("amount")
        val unit = args.requireString("unit")
        val milliliters = VoiceArguments.waterMilliliters(amount, unit)
            ?: fail(ToolError.INVALID_ARGUMENTS, "Give a positive amount with unit ml, l, oz, cup or glass.")
        if (milliliters > MAX_WATER_ML) fail(ToolError.INVALID_ARGUMENTS, "That is more than ${MAX_WATER_ML / 1000} liters at once.")
        val date = args.string("date")?.let(::parseDateArg) ?: ports.diaryDate()
        val timestamp = timestampFor(date, null)
        return confirmable(spec, args, VoiceChange.LogWater(milliliters)) {
            val entry = WaterEntry(date = timestamp, milliliters = milliliters)
            ports.addWater(entry)
            val dayTotal = ports.waterEntries().filter { localDate(it.date) == date }.sumOf { it.milliliters }
            Performed(
                ToolResults.ok {
                    put("water_ml", milliliters)
                    put("date", VoiceArguments.formatDate(date))
                    put("day_total_ml", dayTotal)
                },
                undo = { ports.deleteWater(entry.id) }
            )
        }
    }

    private suspend fun startFast(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val hours = args.requireNumber("goal_hours")
        val minutes = (hours * 60).roundToInt()
        if (minutes !in MIN_FAST_MINUTES..MAX_FAST_MINUTES) {
            fail(ToolError.INVALID_ARGUMENTS, "goal_hours must be between 1 and 168.")
        }
        if (!ports.trackingPrefs().fastingEnabled) {
            fail(ToolError.UNSUPPORTED, "Fasting tracking is off. The user can turn it on in Settings, Tracking & Notifications.")
        }
        if (ports.activeFast() != null) fail(ToolError.INVALID_ARGUMENTS, "A fast is already running.")
        val goalHours = (minutes / 60.0).roundToInt()
        return confirmable(spec, args, VoiceChange.StartFast(goalHours)) {
            val session = ports.startFast(minutes)
                ?: fail(ToolError.INVALID_ARGUMENTS, "The fast could not start because it overlaps another fast.")
            Performed(
                ToolResults.ok {
                    put("started_at", session.startedAt.toString())
                    put("goal_hours", session.goalMinutes / 60.0)
                },
                undo = { ports.deleteFast(session.id) }
            )
        }
    }

    private suspend fun endFast(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val active = ports.activeFast() ?: fail(ToolError.NOT_FOUND, "No fast is running.")
        val elapsed = active.durationSeconds(ports.now()) / 60
        return confirmable(spec, args, VoiceChange.EndFast(elapsed)) {
            if (ports.activeFast()?.id != active.id) {
                fail(ToolError.INVALID_ARGUMENTS, "The running fast changed. Read its current state before ending it.")
            }
            val ended = ports.endFast() ?: fail(ToolError.NOT_FOUND, "No fast is running.")
            val seconds = ended.durationSeconds(ports.now())
            Performed(
                ToolResults.ok {
                    put("duration_minutes", seconds / 60)
                    put("goal_hours", ended.goalMinutes / 60.0)
                    put("goal_reached", seconds >= ended.goalMinutes * 60L)
                },
                undo = {
                    if (!ports.reopenFast(ended)) fail(ToolError.UNSUPPORTED, "The fast could not be reopened.")
                }
            )
        }
    }

    private suspend fun logWeight(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val value = args.requireNumber("value")
        val unit = args.requireString("unit")
        val kilograms = VoiceArguments.weightKilograms(value, unit)
            ?: fail(ToolError.INVALID_ARGUMENTS, "Give a positive value with unit kg or lb.")
        if (kilograms < MIN_WEIGHT_KG || kilograms > MAX_WEIGHT_KG) {
            fail(ToolError.INVALID_ARGUMENTS, "That weight looks wrong. Check the number and unit.")
        }
        val date = args.string("date")?.let(::parseDateArg) ?: ports.today()
        val metric = ports.trackingPrefs().weightMetric
        return confirmable(spec, args, VoiceChange.LogWeight(kilograms, metric)) {
            val entry = WeightEntry(date = timestampFor(date, null), weightKg = kilograms)
            ports.addWeight(entry)
            Performed(
                ToolResults.ok {
                    put("kg", round1(kilograms))
                    put("lb", round1(kilograms / VoiceArguments.KILOGRAMS_PER_POUND))
                    put("date", VoiceArguments.formatDate(date))
                },
                undo = { ports.deleteWeight(entry.id) }
            )
        }
    }

    private suspend fun logBodyFat(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val percent = args.requireNumber("percent")
        if (percent < MIN_BODY_FAT || percent > MAX_BODY_FAT) {
            fail(ToolError.INVALID_ARGUMENTS, "percent must be between $MIN_BODY_FAT and $MAX_BODY_FAT.")
        }
        val date = args.string("date")?.let(::parseDateArg) ?: ports.today()
        return confirmable(spec, args, VoiceChange.LogBodyFat(percent)) {
            val entry = BodyFatEntry(date = timestampFor(date, null), bodyFatFraction = percent / 100.0)
            ports.addBodyFat(entry)
            Performed(
                ToolResults.ok {
                    put("percent", round1(percent))
                    put("date", VoiceArguments.formatDate(date))
                },
                undo = { ports.deleteBodyFat(entry.id) }
            )
        }
    }

    // MARK: - Workouts

    private suspend fun describeWorkout(args: ToolArgs): JsonObject {
        val description = args.requireString("description").take(MAX_DESCRIPTION_CHARS)
        val date = args.string("date")?.let(::parseDateArg) ?: ports.workoutDiaryDate() ?: ports.today()
        val draft = try {
            ports.describeWorkout(description, date)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (question: WorkoutClarification) {
            return ToolResults.error(ToolError.INVALID_ARGUMENTS, question.message.orEmpty()) {
                put("question", question.message.orEmpty())
                putJsonArray("options") { question.options.forEach { add(JsonPrimitive(it)) } }
            }
        } catch (failure: IllegalArgumentException) {
            return ToolResults.error(ToolError.INVALID_ARGUMENTS, failure.message ?: "Describe the workout again.")
        } catch (error: AiError) {
            return ToolResults.error(
                if (error.kind == AiErrorKind.HOSTED_QUOTA_EXCEEDED) ToolError.QUOTA_EXCEEDED else ToolError.ANALYSIS_FAILED,
                error.kind.defaultMessage
            )
        } catch (error: Throwable) {
            return ToolResults.error(ToolError.ANALYSIS_FAILED, "The workout could not be read. Please try again.")
        }
        val id = drafts.nextId()
        drafts.add(VoiceDraft.Workout(id, draft))
        val items = draft.exercises.map { exercise ->
            DraftItem(name = exercise.name, detail = workoutDetail(exercise))
        }
        addCard(VoiceCard.Draft(nextCardId(), id, isWorkout = true, title = draft.date, items = items, totals = null))
        return ToolResults.ok {
            put("draft_id", id)
            put("date", draft.date)
            putJsonArray("exercises") {
                draft.exercises.forEach { exercise ->
                    add(buildJsonObject {
                        put("name", exercise.name)
                        exercise.minutes.toDoubleOrNull()?.let { put("minutes", it) }
                        if (exercise.sets.isNotEmpty()) {
                            putJsonArray("sets") {
                                exercise.sets.forEach { set ->
                                    add(buildJsonObject {
                                        set.reps.toIntOrNull()?.let { put("reps", it) }
                                        set.weight.toDoubleOrNull()?.let {
                                            put("weight", it)
                                            put("unit", exercise.unit)
                                        }
                                        set.rpe.toDoubleOrNull()?.let { put("rpe", it) }
                                    })
                                }
                            }
                        }
                    })
                }
            }
            put("logged", false)
        }
    }

    private fun workoutDetail(exercise: com.apoorvdarshan.calorietracker.models.WorkoutTextExercise): String {
        val parts = mutableListOf<String>()
        if (exercise.sets.isNotEmpty()) {
            val reps = exercise.sets.map { it.reps }.distinct()
            val weights = exercise.sets.map { it.weight }.distinct()
            parts += if (reps.size == 1) "${exercise.sets.size} × ${reps.first()}" else "${exercise.sets.size} sets"
            if (weights.size == 1 && weights.first().isNotBlank()) parts += "${weights.first()} ${exercise.unit}"
        }
        exercise.minutes.takeIf { it.isNotBlank() }?.let { parts += "$it min" }
        return parts.joinToString(" · ")
    }

    private suspend fun logWorkout(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val draftId = args.requireString("draft_id")
        val draft = drafts.get(draftId) as? VoiceDraft.Workout
            ?: fail(ToolError.NOT_FOUND, "No workout draft $draftId in this conversation.")
        if (draft.logged) fail(ToolError.INVALID_ARGUMENTS, "$draftId is already saved.")
        val date = runCatching { LocalDate.parse(draft.draft.date) }.getOrNull() ?: ports.today()
        return confirmable(spec, args, VoiceChange.LogWorkout(draft.draft.exercises.size, date)) {
            performWorkoutLog(draft)
        }
    }

    private suspend fun performWorkoutLog(draft: VoiceDraft.Workout): Performed {
        if (draft.logged) fail(ToolError.INVALID_ARGUMENTS, "${draft.id} is already saved.")
        val write = try {
            ports.saveWorkout(draft.draft)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ToolFailure) {
            throw failure
        } catch (error: IllegalArgumentException) {
            fail(ToolError.INVALID_ARGUMENTS, error.message ?: "The workout could not be saved.")
        } catch (error: IllegalStateException) {
            fail(ToolError.INVALID_ARGUMENTS, error.message ?: "The workout could not be saved.")
        }
        draft.logged = true
        markDraftCardLogged(draft.id, true)
        return Performed(
            ToolResults.ok {
                put("saved", true)
                put("exercises", write.exerciseCount)
                put("date", VoiceArguments.formatDate(write.date))
            },
            undo = {
                write.restore()
                draft.logged = false
                markDraftCardLogged(draft.id, false)
            }
        )
    }

    private suspend fun logCardio(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val activity = args.requireString("activity").take(MAX_NAME_CHARS)
        val minutesValue = args.requireNumber("minutes")
        val minutes = minutesValue.roundToInt()
        if (minutes !in 1..MAX_CARDIO_MINUTES) fail(ToolError.INVALID_ARGUMENTS, "minutes must be 1 to $MAX_CARDIO_MINUTES.")
        val calories = args.number("calories")?.roundToInt()?.also {
            if (it !in 1..MAX_CARDIO_CALORIES) fail(ToolError.INVALID_ARGUMENTS, "calories must be 1 to $MAX_CARDIO_CALORIES.")
        }
        val date = args.string("date")?.let(::parseDateArg) ?: ports.today()
        return confirmable(spec, args, VoiceChange.LogCardio(activity, minutes)) {
            val write = try {
                ports.logCardio(activity, minutes, calories, date)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ToolFailure) {
                throw failure
            } catch (error: IllegalArgumentException) {
                fail(ToolError.INVALID_ARGUMENTS, error.message ?: "That activity could not be logged.")
            }
            Performed(
                ToolResults.ok {
                    put("activity", write.name)
                    put("minutes", minutes)
                    write.caloriesBurned?.let { put("day_calories_burned", it) }
                    put("date", VoiceArguments.formatDate(write.date))
                },
                undo = write.restore
            )
        }
    }

    // MARK: - Settings

    private suspend fun getSettings(): JsonObject {
        val settings = ports.settings(context.provider)
        return ToolResults.ok {
            put("theme_color", settings.themeColor)
            put("appearance", settings.appearance)
            put("units", if (settings.metric) "metric" else "imperial")
            settings.calorieGoal?.let { put("calorie_goal", it) }
            settings.proteinGoalG?.let { put("protein_goal_g", it) }
            settings.carbsGoalG?.let { put("carbs_goal_g", it) }
            settings.fatGoalG?.let { put("fat_goal_g", it) }
            put("water_goal_ml", settings.waterGoalMl)
            put("assistant_voice", settings.voice)
            put("assistant_auto_capture", settings.autoCapture)
            put("assistant_confirm_saves", settings.confirmSaves)
            put("adaptive_goals", settings.adaptiveGoals)
            putJsonObject("allowed") {
                putJsonArray("theme_color") { VoiceSettingValues.themeColorKeys.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("assistant_voice") { settings.voices.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    private suspend fun updateSetting(args: ToolArgs, spec: VoiceToolSpec): JsonObject {
        val key = VoiceSettingKey.fromWire(args.requireString("key"))
            ?: fail(ToolError.UNSUPPORTED, "That setting can't be changed by voice on Android.")
        val settings = ports.settings(context.provider)
        val value = VoiceSettingValues.parse(key, args.requireString("value"), settings.voices)
        if (key in GOAL_KEYS && settings.adaptiveGoals) {
            fail(
                ToolError.UNSUPPORTED,
                "Adaptive Goals is on and sets the targets every week. The user can turn it off in Settings, Goals & Nutrition, first."
            )
        }
        return confirmable(spec, args, VoiceChange.UpdateSetting(key, value.display)) {
            when (val applied = ports.applySetting(key, value, context.provider)) {
                is SettingApplied.Rejected -> fail(applied.error, applied.message)
                is SettingApplied.Done -> {
                    val before = context
                    applyToSession(key, value)
                    Performed(
                        ToolResults.ok {
                            put("key", key.wireValue)
                            put("value", value.display)
                            applied.note?.let { put("note", it) }
                        },
                        undo = {
                            applied.restore()
                            context = context.copy(confirmSaves = before.confirmSaves, autoCapture = before.autoCapture)
                        }
                    )
                }
            }
        }
    }

    /** Assistant settings take effect in this session straight away. */
    private fun applyToSession(key: VoiceSettingKey, value: SettingValue) {
        val on = (value as? SettingValue.Toggle)?.on ?: return
        context = when (key) {
            VoiceSettingKey.ASSISTANT_CONFIRM_SAVES -> context.copy(confirmSaves = on)
            VoiceSettingKey.ASSISTANT_AUTO_CAPTURE -> context.copy(autoCapture = on)
            else -> context
        }
    }

    private fun endConversation(): JsonObject {
        onEndConversation()
        return ToolResults.ok { put("closing", true) }
    }

    // MARK: - Coach data tools

    private suspend fun coachData(name: String, args: ToolArgs): JsonObject {
        val raw = ports.coachTools().execute(name, plainArguments(args.raw))
        val fitted = ToolResultLimiter.fit(raw, MAX_TOOL_RESULT_CHARS)
        val payload = runCatching { Json.parseToJsonElement(fitted).jsonObject }.getOrNull()
            ?: return ToolResults.error(ToolError.ANALYSIS_FAILED, "The data could not be read.")
        (payload["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            return ToolResults.error(ToolError.INVALID_ARGUMENTS, it.content)
        }
        return JsonObject(mapOf("ok" to JsonPrimitive(true)) + payload)
    }

    private fun plainArguments(raw: JsonObject): Map<String, Any?> = raw.mapValues { (_, value) ->
        when (value) {
            is JsonPrimitive -> when {
                value.isString -> value.content
                value.longOrNull != null -> value.longOrNull
                value.doubleOrNull != null -> value.doubleOrNull
                value.booleanOrNull != null -> value.booleanOrNull
                else -> null
            }
            is JsonArray, is JsonObject -> value.toString()
        }
    }

    // MARK: - Helpers

    private suspend fun findEntry(rawId: String): FoodEntry {
        val id = runCatching { UUID.fromString(rawId.trim()) }.getOrNull()
            ?: fail(ToolError.NOT_FOUND, "No food entry $rawId. Use get_food_entries for entry ids.")
        return ports.foodEntries().firstOrNull { it.id == id }
            ?: fail(ToolError.NOT_FOUND, "No food entry $rawId. Use get_food_entries for entry ids.")
    }

    private fun parseDateArg(raw: String): LocalDate {
        val date = VoiceArguments.parseDate(raw, ports.today())
            ?: fail(ToolError.INVALID_ARGUMENTS, "Dates are yyyy-MM-dd, today or yesterday.")
        if (date.isAfter(ports.today())) fail(ToolError.INVALID_ARGUMENTS, "The date can't be in the future.")
        return date
    }

    private fun parseTimeArg(raw: String): LocalTime =
        VoiceArguments.parseTime(raw) ?: fail(ToolError.INVALID_ARGUMENTS, "Times are HH:mm, 24-hour.")

    /** Like Home's log date: now for today, the current time of day on another day, or an exact time. */
    private fun timestampFor(date: LocalDate, time: LocalTime?): Instant {
        if (time == null && date == ports.today()) return ports.now()
        val clockTime = time ?: localTime(ports.now())
        return date.atTime(clockTime).atZone(ports.zone()).toInstant()
    }

    private fun localDate(instant: Instant): LocalDate = instant.atZone(ports.zone()).toLocalDate()

    private fun localTime(instant: Instant): LocalTime = instant.atZone(ports.zone()).toLocalTime().withSecond(0).withNano(0)

    companion object {
        private const val TAG = "VoiceTools"
        const val APP_NOTE = "[Fud AI app]"
        private const val MAX_CARDS = 6
        private const val COMPLETED_MEMORY_MILLIS = 120_000L
        private const val MAX_TOOL_RESULT_CHARS = 60_000
        private const val MAX_PICKED_PHOTOS = 4
        private const val MAX_NOTE_CHARS = 500
        private const val MAX_DESCRIPTION_CHARS = 2_000
        private const val MAX_NAME_CHARS = 120
        private const val DEFAULT_SEARCH_LIMIT = 8
        private const val MAX_SEARCH_LIMIT = 20
        private const val MAX_SERVINGS = 20.0
        private const val MAX_CALORIES = 10_000.0
        private const val MAX_MACRO_G = 1_000.0
        private const val MAX_WATER_ML = 5_000
        private const val MIN_FAST_MINUTES = 60
        private const val MAX_FAST_MINUTES = 7 * 24 * 60
        private const val MIN_WEIGHT_KG = 20.0
        private const val MAX_WEIGHT_KG = 400.0
        private const val MIN_BODY_FAT = 2.0
        private const val MAX_BODY_FAT = 75.0
        private const val MAX_CARDIO_MINUTES = 1_440
        private const val MAX_CARDIO_CALORIES = 5_000

        private val GOAL_KEYS = setOf(
            VoiceSettingKey.CALORIE_GOAL,
            VoiceSettingKey.PROTEIN_GOAL_G,
            VoiceSettingKey.CARBS_GOAL_G,
            VoiceSettingKey.FAT_GOAL_G
        )

        internal fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

        internal fun draftItems(analysis: FoodAnalysis): List<DraftItem> =
            if (analysis.ingredients.size > 1) {
                analysis.ingredients.map {
                    DraftItem(it.name, "${it.grams.roundToInt()} g", it.calories, it.protein, it.carbs, it.fat)
                }
            } else {
                listOf(
                    DraftItem(
                        analysis.name,
                        servingText(analysis),
                        analysis.calories,
                        analysis.protein,
                        analysis.carbs,
                        analysis.fat
                    )
                )
            }

        private fun servingText(analysis: FoodAnalysis): String? {
            val grams = analysis.servingSizeGrams.takeIf { analysis.servingSizeIsKnown && it.isFinite() && it > 0 }
            val unit = analysis.selectedServingUnit?.takeIf { it.isNotBlank() && it != "serving" }
            val quantity = analysis.selectedServingQuantity?.takeIf { it > 0 }
            val gramsText = grams?.let { "${it.roundToInt()} g" }
            return when {
                unit != null && quantity != null -> "${formatQuantity(quantity)} $unit" + (gramsText?.let { " ($it)" } ?: "")
                else -> gramsText
            }
        }

        internal fun servingText(entry: FoodEntry): String? {
            val unit = entry.selectedServingUnit?.takeIf { it.isNotBlank() }
            val quantity = entry.selectedServingQuantity?.takeIf { it > 0 }
            val gramsText = entry.servingSizeGrams?.takeIf { it.isFinite() && it > 0 }?.let { "${it.roundToInt()} g" }
            return when {
                unit != null && quantity != null && unit != "serving" -> "${formatQuantity(quantity)} $unit" + (gramsText?.let { " ($it)" } ?: "")
                unit == "serving" && quantity != null -> "${formatQuantity(quantity)} serving"
                else -> gramsText
            }
        }

        private fun formatQuantity(value: Double): String =
            if (abs(value - value.roundToInt()) < 0.01) value.roundToInt().toString()
            else String.format(Locale.US, "%.1f", value)

        /** A diary entry from an analysis, like Home's review sheet, scaled by [servings]. */
        internal fun foodEntryFromAnalysis(
            analysis: FoodAnalysis,
            id: UUID,
            timestamp: Instant,
            meal: MealType,
            servings: Double,
            source: FoodSource,
            imageFilenames: List<String>
        ): FoodEntry {
            fun s(value: Double?) = value?.let { it * servings }
            return FoodEntry(
                id = id,
                name = analysis.name,
                calories = (analysis.calories * servings).roundToInt(),
                protein = analysis.protein * servings,
                carbs = analysis.carbs * servings,
                fat = analysis.fat * servings,
                timestamp = timestamp,
                imageFilename = imageFilenames.firstOrNull(),
                additionalImageFilenames = imageFilenames.drop(1),
                emoji = analysis.emoji,
                source = source,
                mealType = meal,
                sugar = s(analysis.sugar),
                addedSugar = s(analysis.addedSugar),
                fiber = s(analysis.fiber),
                saturatedFat = s(analysis.saturatedFat),
                monounsaturatedFat = s(analysis.monounsaturatedFat),
                polyunsaturatedFat = s(analysis.polyunsaturatedFat),
                cholesterol = s(analysis.cholesterol),
                caffeine = s(analysis.caffeine),
                supplementalNutrients = analysis.supplementalNutrients.mapValues { (_, value) -> value * servings },
                sodium = s(analysis.sodium),
                potassium = s(analysis.potassium),
                transFat = s(analysis.transFat),
                calcium = s(analysis.calcium),
                iron = s(analysis.iron),
                magnesium = s(analysis.magnesium),
                zinc = s(analysis.zinc),
                vitaminA = s(analysis.vitaminA),
                vitaminC = s(analysis.vitaminC),
                vitaminD = s(analysis.vitaminD),
                vitaminB12 = s(analysis.vitaminB12),
                vitaminE = s(analysis.vitaminE),
                vitaminK = s(analysis.vitaminK),
                folate = s(analysis.folate),
                omega3 = s(analysis.omega3),
                servingSizeGrams = if (analysis.servingSizeIsKnown) analysis.servingSizeGrams * servings else null,
                servingUnitOptions = if (analysis.servingSizeIsKnown) {
                    analysis.servingUnitOptions.map { it.copy(quantity = it.quantity?.times(servings)) }
                } else {
                    emptyList()
                },
                selectedServingUnit = if (analysis.servingSizeIsKnown) {
                    analysis.selectedServingUnit?.takeIf { analysis.servingUnitOptions.isNotEmpty() }
                } else {
                    "serving"
                },
                selectedServingQuantity = if (analysis.servingSizeIsKnown) {
                    analysis.selectedServingQuantity?.takeIf { analysis.servingUnitOptions.isNotEmpty() }?.times(servings)
                } else {
                    servings
                },
                customNote = analysis.customNote,
                progressiveMeal = analysis.progressiveMeal,
                ingredients = analysis.ingredients.map { it.scaled(servings) },
                productMetadata = analysis.productMetadata
            )
        }
    }
}

/** The same food, [factor] times as much. */
internal fun FoodEntry.scaledBy(factor: Double): FoodEntry {
    if (abs(factor - 1.0) < 1e-9) return this
    fun s(value: Double?) = value?.let { it * factor }
    return copy(
        calories = (calories * factor).roundToInt(),
        protein = protein * factor,
        carbs = carbs * factor,
        fat = fat * factor,
        sugar = s(sugar),
        addedSugar = s(addedSugar),
        fiber = s(fiber),
        saturatedFat = s(saturatedFat),
        monounsaturatedFat = s(monounsaturatedFat),
        polyunsaturatedFat = s(polyunsaturatedFat),
        cholesterol = s(cholesterol),
        caffeine = s(caffeine),
        supplementalNutrients = supplementalNutrients.mapValues { (_, value) -> value * factor },
        sodium = s(sodium),
        potassium = s(potassium),
        transFat = s(transFat),
        calcium = s(calcium),
        iron = s(iron),
        magnesium = s(magnesium),
        zinc = s(zinc),
        vitaminA = s(vitaminA),
        vitaminC = s(vitaminC),
        vitaminD = s(vitaminD),
        vitaminB12 = s(vitaminB12),
        vitaminE = s(vitaminE),
        vitaminK = s(vitaminK),
        folate = s(folate),
        omega3 = s(omega3),
        servingSizeGrams = s(servingSizeGrams),
        servingUnitOptions = servingUnitOptions.map { it.copy(quantity = it.quantity?.times(factor)) },
        selectedServingQuantity = s(selectedServingQuantity),
        ingredients = ingredients.map { it.scaled(factor) }
    )
}
