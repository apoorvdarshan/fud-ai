package com.apoorvdarshan.calorietracker.services.voice

import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealIngredient
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.WorkoutClarification
import com.apoorvdarshan.calorietracker.models.WorkoutTextDraft
import com.apoorvdarshan.calorietracker.models.WorkoutTextExercise
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class VoiceToolExecutorTest {
    private val ports = FakeVoiceAppPorts()
    private var nowMillis = 1_000_000L

    private fun executor(confirmSaves: Boolean = true, provider: LiveProvider = LiveProvider.GEMINI) = VoiceToolExecutor(
        ports = ports,
        catalog = RepoFiles.catalog,
        context = VoiceSessionContext(provider, confirmSaves = confirmSaves, autoCapture = false),
        clock = { nowMillis }
    )

    private var nextCall = 1

    private fun VoiceToolExecutor.call(name: String, args: JsonObject = JsonObject(emptyMap())): JsonObject =
        runBlocking { execute(LiveToolCall("call_${nextCall++}", name, args)) }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is String -> put(key, value)
                is Number -> put(key, value)
                is Boolean -> put(key, value)
                is JsonObject -> put(key, value)
                else -> error("unsupported")
            }
        }
    }

    private fun JsonObject.ok(): Boolean = this["ok"]!!.jsonPrimitive.boolean
    private fun JsonObject.error(): String? = this["error"]?.jsonPrimitive?.content

    // MARK: - Confirmation

    @Test
    fun foodSaveClosesCameraOnlyAfterConfirmation() {
        val executor = executor()
        ports.camera = VoiceCameraMode.FOOD
        val item = buildJsonObject { put("name", "Banana"); put("calories", 105) }
        assertEquals("confirmation_required", executor.call("log_food", args("item" to item)).error())
        assertEquals(VoiceCameraMode.FOOD, ports.camera)
        assertTrue(executor.call("log_food", args("item" to item, "user_confirmed" to true)).ok())
        assertNull(ports.camera)
        assertEquals(1, ports.foods.size)
    }

    @Test
    fun aCardTapAndModelCallCannotLogTheSameDraftTwice() = runBlocking {
        val executor = executor(confirmSaves = false)
        val draft = executor.call("describe_food", args("description" to "eggs"))
        val draftId = draft["draft_id"]!!.jsonPrimitive.content
        val card = executor.cards.value.single() as VoiceCard.Draft
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        ports.foodWriteGate = {
            writeStarted.complete(Unit)
            allowWrite.await()
        }
        val tapped = async(start = CoroutineStart.UNDISPATCHED) { executor.logDraftFromCard(card.id) }
        writeStarted.await()
        val called = async(start = CoroutineStart.UNDISPATCHED) {
            executor.execute(LiveToolCall("log-after-tap", "log_food", args("draft_id" to draftId)))
        }
        allowWrite.complete(Unit)
        assertTrue(tapped.await()!!.contains("It is logged"))
        assertEquals("invalid_arguments", called.await().error())
        assertEquals(1, ports.foods.size)

        executor.undoCard((executor.cards.value.last() as VoiceCard.Undo).id)
        assertTrue(ports.foods.isEmpty())
        assertTrue(executor.call("log_food", args("draft_id" to draftId)).ok())
        assertEquals(1, ports.foods.size)
    }

    @Test
    fun confirmingOneWaterChangePreservesOtherPendingChanges() {
        val executor = executor()
        executor.call("log_water", args("amount" to 100, "unit" to "ml"))
        executor.call("log_water", args("amount" to 200, "unit" to "ml"))
        executor.call("log_water", args("amount" to 100, "unit" to "ml", "user_confirmed" to true))
        val remaining = executor.cards.value.filterIsInstance<VoiceCard.Confirmation>().single()
        assertEquals(VoiceChange.LogWater(200), remaining.change)
        assertEquals(100, ports.water.single().milliliters)
    }

    @Test
    fun failedCardConfirmationCanBeRetriedAndDoesNotClaimSuccess() = runBlocking {
        val executor = executor()
        executor.call("log_water", args("amount" to 100, "unit" to "ml"))
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        ports.waterWriteError = IllegalStateException("storage unavailable")
        assertTrue(executor.confirmCard(card.id)!!.contains("but it failed"))
        assertTrue(ports.water.isEmpty())
        ports.waterWriteError = null
        assertTrue(executor.call("log_water", args("amount" to 100, "unit" to "ml", "user_confirmed" to true)).ok())
        assertEquals(1, ports.water.size)
    }

    @Test
    fun photoPickerUsesTheFoodPipelineLimit() {
        val executor = executor()
        ports.maxPhotos = 3
        ports.picked = PickOutcome.Picked(List(4) { byteArrayOf(1) })
        assertTrue(executor.call("pick_photos").ok())
        assertEquals(3, ports.requestedMaxPhotos)
        assertEquals(3, ports.analyzedPhotoCount)
    }

    @Test
    fun anOldConfirmationCannotOverwriteALaterDiaryEdit() = runBlocking {
        val executor = executor()
        val original = FoodEntry(name = "Lunch", calories = 300, protein = 10.0, carbs = 20.0, fat = 5.0,
            timestamp = ports.instant, source = FoodSource.MANUAL)
        ports.foods += original
        executor.call("update_food_entry", args("entry_id" to original.id.toString(), "calories" to 400))
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        val edited = original.copy(name = "Edited in diary", calories = 500)
        ports.foods[0] = edited
        assertTrue(executor.confirmCard(card.id)!!.contains("but it failed"))
        assertEquals(edited, ports.foods.single())
    }

    @Test
    fun undoAllowsTheSameChangeToBeConfirmedAgain() = runBlocking {
        val executor = executor()
        executor.call("log_water", args("amount" to 250, "unit" to "ml"))
        executor.confirmCard((executor.cards.value.single() as VoiceCard.Confirmation).id)
        executor.undoCard((executor.cards.value.single() as VoiceCard.Undo).id)
        assertTrue(ports.water.isEmpty())
        assertTrue(executor.call("log_water", args("amount" to 250, "unit" to "ml", "user_confirmed" to true)).ok())
        assertEquals(250, ports.water.single().milliliters)
    }

    @Test
    fun aWriteNeedingConfirmationChangesNothingAndShowsACard() {
        val executor = executor()
        val result = executor.call("log_water", args("amount" to 500, "unit" to "ml"))
        assertFalse(result.ok())
        assertEquals("confirmation_required", result.error())
        assertEquals("log 500 ml of water", result["summary"]!!.jsonPrimitive.content)
        assertTrue(ports.water.isEmpty())
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        assertEquals(VoiceChange.LogWater(500), card.change)
        assertFalse(card.destructive)
        assertTrue(executor.hasPendingConfirmation)
    }

    @Test
    fun theConfirmedCallWritesOfferUndoAndClearsTheCard() {
        val executor = executor()
        executor.call("log_water", args("amount" to 2, "unit" to "cup"))
        val result = executor.call("log_water", args("amount" to 2, "unit" to "cup", "user_confirmed" to true))
        assertTrue(result.ok())
        assertEquals(480, result["water_ml"]!!.jsonPrimitive.int)
        assertTrue(result["undo_available"]!!.jsonPrimitive.boolean)
        assertEquals(480, ports.water.single().milliliters)
        val cards = executor.cards.value
        assertTrue(cards.none { it is VoiceCard.Confirmation })
        assertTrue(cards.single() is VoiceCard.Undo)
        assertFalse(executor.hasPendingConfirmation)
    }

    @Test
    fun undoRestoresWithinEightSecondsOnly() = runBlocking {
        val executor = executor(confirmSaves = false)
        executor.call("log_water", args("amount" to 1, "unit" to "glass"))
        val undo = executor.cards.value.single() as VoiceCard.Undo
        assertEquals(UndoRegistry.WINDOW_MILLIS, undo.windowMillis)

        val note = executor.undoCard(undo.id)
        assertNotNull(note)
        assertTrue(note!!.startsWith(VoiceToolExecutor.APP_NOTE))
        assertTrue(ports.water.isEmpty())
        assertTrue(executor.cards.value.isEmpty())

        executor.call("log_water", args("amount" to 1, "unit" to "glass"))
        val late = executor.cards.value.single() as VoiceCard.Undo
        nowMillis += UndoRegistry.WINDOW_MILLIS
        executor.expireUndoCards()
        assertTrue(executor.cards.value.isEmpty())
        assertNull(executor.undoCard(late.id))
        assertEquals(1, ports.water.size)
    }

    @Test
    fun tappingConfirmPerformsTheChangeAndTheModelsRepeatIsNotDoubled() = runBlocking {
        val executor = executor()
        val request = args("amount" to 750, "unit" to "ml")
        executor.call("log_water", request)
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        val note = executor.confirmCard(card.id)!!
        assertTrue(note.contains("do not call log_water again"))
        assertEquals(750, ports.water.single().milliliters)

        val repeat = executor.call("log_water", args("amount" to 750, "unit" to "ml", "user_confirmed" to true))
        assertTrue(repeat.ok())
        assertTrue(repeat["already_done"]!!.jsonPrimitive.boolean)
        assertEquals(1, ports.water.size)
    }

    @Test
    fun cancellingACardChangesNothing() = runBlocking {
        val executor = executor()
        executor.call("start_fast", args("goal_hours" to 16))
        val card = executor.cards.value.single() as VoiceCard.Confirmation
        val note = executor.cancelCard(card.id)!!
        assertTrue(note.contains("Nothing changed"))
        assertTrue(ports.fasts.isEmpty())
        assertTrue(executor.cards.value.isEmpty())
    }

    @Test
    fun withConfirmBeforeSavingOffWritesHappenButDeletesStillAsk() {
        val executor = executor(confirmSaves = false)
        assertTrue(executor.call("log_weight", args("value" to 72.5, "unit" to "kg")).ok())
        assertEquals(1, ports.weights.size)

        val entry = FoodEntry(name = "Apple", calories = 95, protein = 0.5, carbs = 25.0, fat = 0.3, source = FoodSource.MANUAL)
        ports.foods += entry
        val delete = executor.call("delete_food_entry", args("entry_id" to entry.id.toString()))
        assertEquals("confirmation_required", delete.error())
        assertEquals(1, ports.foods.size)
        val card = executor.cards.value.last() as VoiceCard.Confirmation
        assertTrue(card.destructive)

        val confirmed = executor.call("delete_food_entry", args("entry_id" to entry.id.toString(), "user_confirmed" to true))
        assertTrue(confirmed.ok())
        assertTrue(ports.foods.isEmpty())
        assertTrue("Deletes have no undo", executor.cards.value.none { it is VoiceCard.Undo && it.change is VoiceChange.DeleteFood })
        assertNull(confirmed["undo_available"])
    }

    // MARK: - Drafts and logging

    @Test
    fun describeFoodMakesADraftThatLogFoodLogsWithServings() {
        val executor = executor(confirmSaves = false)
        val draft = executor.call("describe_food", args("description" to "two eggs and toast"))
        assertTrue(draft.ok())
        val draftId = draft["draft_id"]!!.jsonPrimitive.content
        assertEquals(320, draft["totals"]!!.jsonObject["calories"]!!.jsonPrimitive.int)
        assertFalse(draft["logged"]!!.jsonPrimitive.boolean)
        assertTrue(ports.foods.isEmpty())
        val card = executor.cards.value.single() as VoiceCard.Draft
        assertEquals(draftId, card.draftId)
        assertFalse(card.logged)

        val logged = executor.call("log_food", args("draft_id" to draftId, "servings" to 2, "meal" to "dinner", "time" to "19:15"))
        assertTrue(logged.ok())
        val entry = ports.foods.single()
        assertEquals(640, entry.calories)
        assertEquals(36.0, entry.protein, 0.001)
        assertEquals(MealType.DINNER, entry.mealType)
        assertEquals(FoodSource.TEXT_INPUT, entry.source)
        assertEquals(Instant.parse("2026-10-16T19:15:00Z"), entry.timestamp)
        assertEquals(entry.id.toString(), logged["entry_ids"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue((executor.cards.value.first { it is VoiceCard.Draft } as VoiceCard.Draft).logged)
    }

    @Test
    fun photoDraftsStorePhotosWhenLoggedAndCloseTheCamera() {
        val executor = executor(confirmSaves = false)
        assertEquals("camera_unavailable", executor.call("capture_photo").error())
        assertTrue(executor.call("open_camera", args("mode" to "food")).ok())
        assertEquals(VoiceCameraMode.FOOD, ports.camera)
        val draft = executor.call("capture_photo", args("note" to "half portion"))
        assertTrue(draft.ok())
        assertNull(ports.camera)
        assertTrue(ports.storedImages.isEmpty())
        executor.call("log_food", args("draft_id" to draft["draft_id"]!!.jsonPrimitive.content))
        val entry = ports.foods.single()
        assertEquals(ports.storedImages.first(), entry.imageFilename)
        assertEquals("half portion", entry.customNote)
        assertEquals(MealType.LUNCH, entry.mealType)
    }

    @Test
    fun multiItemDraftsListEachIngredient() {
        ports.analysis = ports.analysis.copy(
            ingredients = listOf(
                MealIngredient("Egg", 100.0, 150, 12.0, 1.0, 10.0),
                MealIngredient("Toast", 80.0, 170, 6.0, 23.0, 5.0)
            )
        )
        val executor = executor()
        val draft = executor.call("describe_food", args("description" to "eggs on toast"))
        val items = draft["items"]!!.jsonArray
        assertEquals(listOf("Egg", "Toast"), items.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("100 g", items[0].jsonObject["serving"]!!.jsonPrimitive.content)
    }

    @Test
    fun loggingFromTheDraftCardNeedsNoSecondConfirmation() = runBlocking {
        val executor = executor(confirmSaves = true)
        executor.call("describe_food", args("description" to "banana"))
        val card = executor.cards.value.single() as VoiceCard.Draft
        val note = executor.logDraftFromCard(card.id)!!
        assertTrue(note.contains("do not log it again"))
        assertEquals("banana", ports.foods.single().name)
        assertTrue(executor.cards.value.any { it is VoiceCard.Undo })
    }

    @Test
    fun logFoodNeedsExactlyOneSource() {
        val executor = executor(confirmSaves = false)
        assertEquals("invalid_arguments", executor.call("log_food").error())
        val both = executor.call(
            "log_food",
            args("draft_id" to "draft_1", "item" to buildJsonObject { put("name", "Tea"); put("calories", 2) })
        )
        assertEquals("invalid_arguments", both.error())
        assertEquals("not_found", executor.call("log_food", args("draft_id" to "draft_99")).error())
    }

    @Test
    fun manualItemsAndDatesAreValidated() {
        val executor = executor(confirmSaves = false)
        val item = buildJsonObject {
            put("name", "Protein shake")
            put("calories", 180)
            put("protein_g", 30)
            put("serving", "1 bottle (330 g)")
        }
        assertEquals("invalid_arguments", executor.call("log_food", args("item" to item, "date" to "tomorrow")).error())
        assertEquals("invalid_arguments", executor.call("log_food", args("item" to item, "date" to "2026-10-17")).error())
        val logged = executor.call("log_food", args("item" to item, "date" to "yesterday", "meal" to "snack"))
        assertTrue(logged.ok())
        val entry = ports.foods.single()
        assertEquals(LocalDate.parse("2026-10-15"), entry.timestamp.atZone(ZoneOffset.UTC).toLocalDate())
        assertEquals(330.0, entry.servingSizeGrams!!, 0.001)
        assertEquals(30.0, entry.protein, 0.001)
        assertEquals(FoodSource.MANUAL, entry.source)
        assertEquals(MealType.SNACK, entry.mealType)
    }

    @Test
    fun aRunningFastPausesFoodLogging() {
        ports.fasts += FastingSession(startedAt = ports.instant.minusSeconds(3_600))
        val executor = executor(confirmSaves = false)
        executor.call("describe_food", args("description" to "rice"))
        val result = executor.call("log_food", args("draft_id" to "draft_1"))
        assertEquals("unsupported", result.error())
        assertTrue(ports.foods.isEmpty())
    }

    @Test
    fun rejectedOrThrowingPhotoLogsDiscardNewImagesAndCanBeRetried() {
        val executor = executor(confirmSaves = false)
        val draft = executor.call("pick_photos")["draft_id"]!!.jsonPrimitive.content
        ports.fasts += FastingSession(startedAt = ports.instant, goalMinutes = 960)
        assertEquals("unsupported", executor.call("log_food", args("draft_id" to draft)).error())
        assertTrue(ports.storedImages.isEmpty())
        ports.fasts.clear()
        ports.foodWriteError = IllegalStateException("write rejected")
        assertEquals("unsupported", executor.call("log_food", args("draft_id" to draft)).error())
        assertTrue(ports.storedImages.isEmpty())
        ports.foodWriteError = CancellationException("session ended")
        try {
            executor.call("log_food", args("draft_id" to draft))
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(ports.storedImages.isEmpty())
        ports.foodWriteError = null
        assertTrue(executor.call("log_food", args("draft_id" to draft)).ok())
        assertEquals(ports.foods.single().allImageFilenames, ports.storedImages)
    }

    @Test
    fun failedPhotoWritePreservesFilesAlreadyAdoptedByPersistedData() {
        val executor = executor(confirmSaves = false)
        val draft = executor.call("pick_photos")["draft_id"]!!.jsonPrimitive.content
        ports.foodWriteGate = {
            ports.foods += FoodEntry(name = "Persisted owner", calories = 1, protein = 0.0, carbs = 0.0,
                fat = 0.0, source = FoodSource.MANUAL, imageFilename = ports.storedImages.single())
        }
        ports.foodWriteError = IllegalStateException("failure after commit")
        assertEquals("unsupported", executor.call("log_food", args("draft_id" to draft)).error())
        assertEquals(ports.foods.single().allImageFilenames, ports.storedImages)
    }

    @Test
    fun searchPreservesDistinctMealsAndIndependentFavoriteSnapshots() {
        val favorite = FoodEntry(name = "Oats", calories = 350, protein = 12.0, carbs = 55.0,
            fat = 9.0, source = FoodSource.MANUAL)
        val diary = favorite.copy(protein = 20.0, servingSizeGrams = 250.0)
        val other = favorite.copy(id = UUID.randomUUID(), carbs = 45.0)
        ports.foods += listOf(diary, other)
        ports.saved += listOf(SavedFood(favorite, SavedFoodKind.FAVORITE),
            SavedFood(diary, SavedFoodKind.FREQUENT), SavedFood(diary, SavedFoodKind.RECENT),
            SavedFood(other, SavedFoodKind.RECENT))
        val executor = executor(confirmSaves = false)
        val results = executor.call("search_foods", args("query" to "oats"))["results"]!!.jsonArray
        val ids = results.map { it.jsonObject["saved_food_id"]!!.jsonPrimitive.content }
        assertEquals(3, ids.size)
        assertTrue(ids.containsAll(listOf("favorite-${favorite.id}", "food-${diary.id}", "food-${other.id}")))
        executor.call("log_food", args("saved_food_id" to "favorite-${favorite.id}"))
        executor.call("log_food", args("saved_food_id" to "food-${diary.id}"))
        assertEquals(12.0, ports.foods[2].protein, 0.001)
        assertEquals(20.0, ports.foods[3].protein, 0.001)
    }

    @Test
    fun searchedDiaryFoodIsResolvedFreshAndCannotBeLoggedAfterDeletion() {
        val original = FoodEntry(name = "Oats", calories = 350, protein = 12.0, carbs = 55.0,
            fat = 9.0, source = FoodSource.MANUAL)
        ports.foods += original
        ports.saved += SavedFood(original, SavedFoodKind.RECENT)
        val executor = executor(confirmSaves = false)
        val id = executor.call("search_foods", args("query" to "oats"))["results"]!!.jsonArray
            .single().jsonObject["saved_food_id"]!!.jsonPrimitive.content
        ports.foods[0] = original.copy(calories = 400)
        assertTrue(executor.call("log_food", args("saved_food_id" to id)).ok())
        assertEquals(400, ports.foods.last().calories)
        ports.foods.removeAt(0)
        assertEquals("not_found", executor.call("log_food", args("saved_food_id" to id, "servings" to 2)).error())
        assertEquals(1, ports.foods.size)
    }

    @Test
    fun savedFoodConfirmationAndFoodLogUndoPreserveLaterEdits() = runBlocking {
        val original = FoodEntry(name = "Oats", calories = 350, protein = 12.0, carbs = 55.0,
            fat = 9.0, source = FoodSource.MANUAL)
        ports.foods += original
        val executor = executor()
        executor.call("log_food", args("saved_food_id" to "food-${original.id}"))
        val confirmation = executor.cards.value.single() as VoiceCard.Confirmation
        ports.foods[0] = original.copy(calories = 400)
        assertTrue(executor.confirmCard(confirmation.id)!!.contains("but it failed"))
        assertEquals(1, ports.foods.size)
        assertTrue(executor.call("log_food", args("saved_food_id" to "food-${original.id}", "user_confirmed" to true)).ok())
        val undo = executor.cards.value.last() as VoiceCard.Undo
        val edited = ports.foods.last().copy(name = "Edited after logging")
        ports.foods[1] = edited
        assertTrue(executor.undoCard(undo.id)!!.contains("Undo failed"))
        assertEquals(edited, ports.foods[1])
    }

    @Test
    fun searchFoodsRanksAndLogsSavedFoods() {
        val oats = FoodEntry(name = "Overnight oats", calories = 350, protein = 12.0, carbs = 55.0, fat = 9.0, source = FoodSource.MANUAL)
        val oatMilk = FoodEntry(name = "Oat milk latte", calories = 140, protein = 3.0, carbs = 20.0, fat = 5.0, source = FoodSource.MANUAL)
        val soup = FoodEntry(name = "Tomato soup", calories = 200, protein = 4.0, carbs = 30.0, fat = 6.0, source = FoodSource.MANUAL)
        ports.saved += SavedFood(oatMilk, SavedFoodKind.RECENT)
        ports.saved += SavedFood(oats, SavedFoodKind.FAVORITE)
        ports.saved += SavedFood(soup, SavedFoodKind.FAVORITE)
        val executor = executor(confirmSaves = false)
        val result = executor.call("search_foods", args("query" to "oat", "limit" to 5))
        val names = result["results"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("Overnight oats", "Oat milk latte"), names)
        val id = result["results"]!!.jsonArray[0].jsonObject["saved_food_id"]!!.jsonPrimitive.content

        assertTrue(executor.call("log_food", args("saved_food_id" to id, "servings" to 1.5)).ok())
        val logged = ports.foods.single()
        assertEquals(525, logged.calories)
        assertTrue(logged.id != oats.id)
    }

    @Test
    fun updateFoodEntryScalesAndUndoRestores() = runBlocking {
        val entry = FoodEntry(
            name = "Pasta",
            calories = 400,
            protein = 14.0,
            carbs = 70.0,
            fat = 8.0,
            source = FoodSource.TEXT_INPUT,
            timestamp = Instant.parse("2026-10-16T08:00:00Z"),
            mealType = MealType.BREAKFAST
        )
        ports.foods += entry
        val executor = executor(confirmSaves = false)
        val result = executor.call(
            "update_food_entry",
            args("entry_id" to entry.id.toString(), "servings" to 0.5, "meal" to "lunch", "time" to "12:45")
        )
        assertTrue(result.ok())
        val updated = ports.foods.single()
        assertEquals(200, updated.calories)
        assertEquals(MealType.LUNCH, updated.mealType)
        assertEquals(Instant.parse("2026-10-16T12:45:00Z"), updated.timestamp)

        val undo = executor.cards.value.single() as VoiceCard.Undo
        executor.undoCard(undo.id)
        assertEquals(entry, ports.foods.single())
        assertEquals("not_found", executor.call("update_food_entry", args("entry_id" to UUID.randomUUID().toString(), "calories" to 5)).error())
        assertEquals("invalid_arguments", executor.call("update_food_entry", args("entry_id" to entry.id.toString())).error())
    }

    // MARK: - Fasting, weight and body fat

    @Test
    fun fastingStartsEndsAndUndoesTheEnd() = runBlocking {
        val executor = executor(confirmSaves = false)
        ports.fastingEnabled = false
        assertEquals("unsupported", executor.call("start_fast", args("goal_hours" to 16)).error())
        ports.fastingEnabled = true
        assertTrue(executor.call("start_fast", args("goal_hours" to 16)).ok())
        assertEquals(16 * 60, ports.fasts.single().goalMinutes)
        assertEquals("invalid_arguments", executor.call("start_fast", args("goal_hours" to 12)).error())

        ports.instant = ports.instant.plusSeconds(17 * 3_600)
        val ended = executor.call("end_fast")
        assertTrue(ended.ok())
        assertTrue(ended["goal_reached"]!!.jsonPrimitive.boolean)
        assertFalse(ports.fasts.single().isActive)
        val undo = executor.cards.value.last() as VoiceCard.Undo
        executor.undoCard(undo.id)
        assertTrue(ports.fasts.single().isActive)
        assertEquals("not_found", executor().also { ports.fasts.clear() }.call("end_fast").error())
    }

    @Test
    fun weightAndBodyFatConvertAndValidate() {
        val executor = executor(confirmSaves = false)
        val weight = executor.call("log_weight", args("value" to 165, "unit" to "lb"))
        assertTrue(weight.ok())
        assertEquals(74.8, ports.weights.single().weightKg, 0.05)
        assertEquals("invalid_arguments", executor.call("log_weight", args("value" to 5, "unit" to "kg")).error())
        assertEquals("invalid_arguments", executor.call("log_weight", args("value" to 70, "unit" to "stone")).error())
        assertTrue(executor.call("log_body_fat", args("percent" to 18.5)).ok())
        assertEquals(0.185, ports.bodyFats.single().bodyFatFraction, 0.0001)
        assertEquals("invalid_arguments", executor.call("log_body_fat", args("percent" to 90)).error())
    }

    // MARK: - Workouts

    @Test
    fun workoutDraftsLogAndUndo() = runBlocking {
        ports.workoutDraft = WorkoutTextDraft(
            "2026-10-16",
            listOf(WorkoutTextExercise(exerciseId = null, name = "Soccer", minutes = "45"))
        )
        val executor = executor(confirmSaves = false)
        val draft = executor.call("describe_workout", args("description" to "45 minutes of soccer"))
        assertTrue(draft.ok())
        assertEquals("Soccer", draft["exercises"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(45.0, draft["exercises"]!!.jsonArray[0].jsonObject["minutes"]!!.jsonPrimitive.double, 0.0)
        val draftId = draft["draft_id"]!!.jsonPrimitive.content
        assertEquals("invalid_arguments", executor.call("log_food", args("draft_id" to draftId)).error())

        assertTrue(executor.call("log_workout", args("draft_id" to draftId)).ok())
        assertEquals(listOf("2026-10-16"), ports.workoutWrites)
        assertEquals("invalid_arguments", executor.call("log_workout", args("draft_id" to draftId)).error())
        val undo = executor.cards.value.last() as VoiceCard.Undo
        executor.undoCard(undo.id)
        assertTrue(ports.workoutWrites.isEmpty())
    }

    @Test
    fun workoutQuestionsComeBackAsQuestions() {
        val executor = executor()
        val asking = VoiceToolExecutor(
            ports = object : VoiceAppPorts by FakeVoiceAppPorts() {
                override suspend fun describeWorkout(description: String, date: LocalDate): WorkoutTextDraft =
                    throw WorkoutClarification("Which variation of curls did you do?", listOf("Barbell", "Dumbbells"))
            },
            catalog = RepoFiles.catalog,
            context = VoiceSessionContext(LiveProvider.GEMINI, confirmSaves = true, autoCapture = false)
        )
        val result = runBlocking { asking.execute(LiveToolCall("c", "describe_workout", args("description" to "curls 3x10"))) }
        assertEquals("invalid_arguments", result.error())
        assertEquals("Which variation of curls did you do?", result["question"]!!.jsonPrimitive.content)
        assertEquals(2, result["options"]!!.jsonArray.size)
        assertTrue(executor.call("log_cardio", args("activity" to "running", "minutes" to 25)).let { it.error() == "confirmation_required" })
    }

    // MARK: - Settings

    @Test
    fun updateSettingValidatesAppliesAndUndoes() = runBlocking {
        val executor = executor(confirmSaves = false)
        val invalid = executor.call("update_setting", args("key" to "theme_color", "value" to "plaid"))
        assertEquals("invalid_arguments", invalid.error())
        assertTrue(invalid["message"]!!.jsonPrimitive.content.contains("roseGold"))

        val result = executor.call("update_setting", args("key" to "theme_color", "value" to "rose gold"))
        assertTrue(result.ok())
        assertEquals("roseGold", ports.settingsValues[VoiceSettingKey.THEME_COLOR])
        executor.undoCard((executor.cards.value.single() as VoiceCard.Undo).id)
        assertNull(ports.settingsValues[VoiceSettingKey.THEME_COLOR])

        assertEquals("unsupported", executor.call("update_setting", args("key" to "font_size", "value" to "large")).error())
        assertEquals("invalid_arguments", executor.call("update_setting", args("key" to "calorie_goal", "value" to "90000")).error())
        ports.adaptiveGoals = true
        assertEquals("unsupported", executor.call("update_setting", args("key" to "calorie_goal", "value" to "2200")).error())
    }

    @Test
    fun turningConfirmBeforeSavingOffAppliesToThisSession() {
        val executor = executor(confirmSaves = true)
        val change = args("key" to "assistant_confirm_saves", "value" to "false")
        assertEquals("confirmation_required", executor.call("update_setting", change).error())
        val confirmed = executor.call("update_setting", args("key" to "assistant_confirm_saves", "value" to "false", "user_confirmed" to true))
        assertTrue(confirmed.ok())
        assertFalse(executor.context.confirmSaves)
        assertTrue(executor.call("log_water", args("amount" to 300, "unit" to "ml")).ok())
        assertEquals(1, ports.water.size)
    }

    @Test
    fun getSettingsListsAllowedValues() {
        val settings = executor(provider = LiveProvider.OPENAI).call("get_settings")
        assertTrue(settings.ok())
        assertEquals("marin", settings["assistant_voice"]!!.jsonPrimitive.content)
        val voices = settings["allowed"]!!.jsonObject["assistant_voice"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(LiveProvider.OPENAI.voices, voices)
    }

    // MARK: - Navigation, camera, errors and Coach data

    @Test
    fun navigationToolsMoveTheApp() {
        val executor = executor()
        assertTrue(executor.call("open_screen", args("screen" to "settings", "settings_section" to "voice_assistant")).ok())
        assertEquals(VoiceScreen.SETTINGS to VoiceSettingsSection.VOICE_ASSISTANT, ports.shownScreens.single())
        assertEquals("invalid_arguments", executor.call("open_screen", args("screen" to "home", "settings_section" to "goals")).error())
        assertEquals("invalid_arguments", executor.call("open_screen", args("screen" to "kitchen")).error())

        assertTrue(executor.call("set_diary_date", args("date" to "yesterday")).ok())
        assertEquals(LocalDate.parse("2026-10-15"), ports.diary)

        val entry = FoodEntry(name = "Salad", calories = 250, protein = 8.0, carbs = 20.0, fat = 14.0, source = FoodSource.MANUAL)
        ports.foods += entry
        assertTrue(executor.call("open_food_entry", args("entry_id" to entry.id.toString())).ok())
        assertEquals(entry, ports.shownEntry)
        assertEquals("not_found", executor.call("open_food_entry", args("entry_id" to "nope")).error())
    }

    @Test
    fun hiddenTabsCannotBeOpenedAndAreReported() {
        val executor = executor()
        ports.hiddenScreens = setOf(VoiceScreen.COACH, VoiceScreen.WORKOUTS)

        assertEquals("unsupported", executor.call("open_screen", args("screen" to "coach")).error())
        assertTrue(ports.shownScreens.isEmpty())
        assertTrue(executor.call("open_screen", args("screen" to "progress")).ok())
        assertEquals(VoiceScreen.PROGRESS to null, ports.shownScreens.single())

        val state = executor.call("get_app_state")
        assertEquals(
            listOf("coach", "workouts"),
            state["hidden_screens"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun waitingToolsReportCancellationTimeoutAndPermission() {
        val executor = executor()
        ports.barcode = BarcodeOutcome.Cancelled
        assertEquals("cancelled", executor.call("scan_barcode").error())
        ports.barcode = BarcodeOutcome.PermissionDenied
        assertEquals("permission_denied", executor.call("scan_barcode").error())
        ports.picked = PickOutcome.TimedOut
        assertEquals("timeout", executor.call("pick_photos").error())
        ports.cameraOutcome = CameraOutcome.PermissionDenied
        assertEquals("permission_denied", executor.call("open_camera").error())
        assertEquals("invalid_arguments", executor.call("open_camera", args("mode" to "barcode")).error())
    }

    @Test
    fun barcodesAndPickedPhotosBecomeDrafts() {
        val executor = executor()
        val scanned = executor.call("scan_barcode")
        assertTrue(scanned.ok())
        assertEquals("Granola bar", scanned["name"]!!.jsonPrimitive.content)
        assertEquals("737628064502", scanned["barcode"]!!.jsonPrimitive.content)
        val picked = executor.call("pick_photos", args("note" to "lunch"))
        assertTrue(picked.ok())
        assertEquals(1, picked["photos"]!!.jsonPrimitive.int)
    }

    @Test
    fun savedLabelScansLogAsNutritionLabels() {
        val executor = executor(confirmSaves = false)
        ports.barcodeProduct = BarcodeProduct(ports.analysis.copy(name = "My label"), null, FoodSource.NUTRITION_LABEL)

        val scanned = executor.call("scan_barcode")
        assertEquals("My label", scanned["name"]!!.jsonPrimitive.content)
        assertTrue(executor.call("log_food", args("draft_id" to scanned["draft_id"]!!.jsonPrimitive.content)).ok())

        assertEquals(FoodSource.NUTRITION_LABEL, ports.foods.single().source)
    }

    @Test
    fun analysisErrorsMapToContractCodes() {
        val executor = executor()
        ports.analysisError = AiError.Hosted(AiErrorKind.HOSTED_QUOTA_EXCEEDED)
        assertEquals("quota_exceeded", executor.call("describe_food", args("description" to "pizza")).error())
        ports.analysisError = AiError.NoApiKey
        val failed = executor.call("describe_food", args("description" to "pizza"))
        assertEquals("analysis_failed", failed.error())
        assertEquals(AiErrorKind.NO_KEY.defaultMessage, failed["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownToolsAreUnsupported() {
        assertEquals("unsupported", executor().call("launch_rocket").error())
    }

    @Test
    fun coachDataToolsAnswerWithOk() {
        ports.foods += FoodEntry(name = "Toast", calories = 120, protein = 4.0, carbs = 20.0, fat = 2.0, source = FoodSource.MANUAL, timestamp = ports.instant)
        val executor = executor()
        val summary = executor.call("get_data_summary")
        assertTrue(summary.ok())
        assertEquals(1, summary["foods"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        val entries = executor.call("get_food_entries", args("from" to "2026-10-16", "to" to "2026-10-16"))
        assertTrue(entries.ok())
        assertEquals("Toast", entries["foods"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val entryId = entries["foods"]!!.jsonArray[0].jsonObject["entry_id"]!!.jsonPrimitive.content
        assertEquals(ports.foods.single().id.toString(), entryId)
        assertTrue(executor.call("open_food_entry", args("entry_id" to entryId)).ok())
        assertTrue(executor.call("update_food_entry", args("entry_id" to entryId, "calories" to 150,
            "user_confirmed" to true)).ok())
        assertEquals(150, ports.foods.single().calories)
        assertTrue(executor.call("delete_food_entry", args("entry_id" to entryId, "user_confirmed" to true)).ok())
        assertTrue(ports.foods.isEmpty())
        assertEquals("invalid_arguments", executor.call("get_exercise_lift_history").error())
    }

    @Test
    fun appStateDescribesTheScreenTotalsAndSession() {
        ports.foods += FoodEntry(name = "Toast", calories = 120, protein = 4.0, carbs = 20.0, fat = 2.0, source = FoodSource.MANUAL, timestamp = ports.instant)
        val state = executor(provider = LiveProvider.XAI).call("get_app_state")
        assertTrue(state.ok())
        assertEquals("home", state["screen"]!!.jsonPrimitive.content)
        assertEquals("2026-10-16", state["diary_date"]!!.jsonPrimitive.content)
        assertEquals(120, state["today_totals"]!!.jsonObject["calories"]!!.jsonPrimitive.int)
        assertFalse(state["camera"]!!.jsonObject["open"]!!.jsonPrimitive.boolean)
        assertEquals("metric", state["unit_system"]!!.jsonPrimitive.content)
        assertEquals("none", state["session"]!!.jsonObject["camera_frames"]!!.jsonPrimitive.content)
    }

    @Test
    fun endConversationAsksTheControllerToClose() {
        val executor = executor()
        var ended = false
        executor.onEndConversation = { ended = true }
        assertTrue(executor.call("end_conversation").ok())
        assertTrue(ended)
    }

    @Test
    fun entriesFromAnalysesScaleEverything() {
        val analysis = ports.analysis.copy(sodium = 400.0, servingSizeGrams = 200.0, emoji = "🍳")
        val entry = VoiceToolExecutor.foodEntryFromAnalysis(
            analysis, UUID.randomUUID(), ports.instant, MealType.BREAKFAST, 1.5, FoodSource.SNAP_FOOD, listOf("a.jpg", "b.jpg")
        )
        assertEquals(480, entry.calories)
        assertEquals(600.0, entry.sodium!!, 0.001)
        assertEquals(300.0, entry.servingSizeGrams!!, 0.001)
        assertEquals("a.jpg", entry.imageFilename)
        assertEquals(listOf("b.jpg"), entry.additionalImageFilenames)
        assertEquals("🍳", entry.emoji)
    }

    @Test
    fun argumentsAcceptNumbersAsText() {
        val executor = executor(confirmSaves = false)
        val result = executor.call(
            "log_water",
            buildJsonObject {
                put("amount", JsonPrimitive("1.5"))
                put("unit", "l")
                putJsonObject("extra") { put("ignored", true) }
            }
        )
        assertTrue(result.ok())
        assertEquals(1_500, ports.water.single().milliliters)
    }
}
