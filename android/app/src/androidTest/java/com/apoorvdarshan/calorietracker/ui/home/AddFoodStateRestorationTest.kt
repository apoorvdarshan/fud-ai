package com.apoorvdarshan.calorietracker.ui.home

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.MealType
import com.apoorvdarshan.calorietracker.models.ServingUnitOption
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises saved-state restoration without writing food entries or changing app preferences. */
@RunWith(AndroidJUnit4::class)
class AddFoodStateRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun textDraftSurvivesRestorationAndIsSubmittedOnlyOnAnalyze() {
        val restoration = StateRestorationTester(compose)
        val draft = "  Rice with beans, about 250g  "
        val submissions = mutableListOf<String>()
        restoration.setContent {
            FudAITheme {
                TextInputDialog(onDismiss = {}, onSubmit = submissions::add)
            }
        }

        compose.onNode(hasSetTextAction()).performTextReplacement(draft)
        restoration.emulateSavedInstanceStateRestore()

        compose.onNode(hasSetTextAction()).assertTextEquals(draft)
        compose.runOnIdle { assertEquals(emptyList<String>(), submissions) }
        compose.onNodeWithText(string(R.string.action_analyze))
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(listOf(draft.trim()), submissions) }
    }

    @Test
    fun manualDraftRestoresEveryNutritionFieldAndChosenMeal() {
        val restoration = StateRestorationTester(compose)
        val submissions = mutableListOf<ManualSubmission>()
        val selectedMeal = if (MealType.currentMeal == MealType.LUNCH) {
            MealType.DINNER
        } else {
            MealType.LUNCH
        }
        restoration.setContent {
            FudAITheme {
                ManualEntryDialog(
                    onDismiss = {},
                    onSave = { name, calories, protein, carbs, fat, fiber, meal ->
                        submissions += ManualSubmission(name, calories, protein, carbs, fat, fiber, meal)
                    }
                )
            }
        }

        val values = listOf("Homemade lunch", "417", "23.5", "48.25", "14.75", "6.5")
        values.forEachIndexed { index, value ->
            compose.onAllNodes(hasSetTextAction())[index].performTextReplacement(value)
        }
        closeSoftKeyboard()
        compose.onNodeWithText(string(R.string.sheet_meal_type)).performClick()
        compose.onNodeWithText(string(selectedMeal.displayNameRes)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        values.forEachIndexed { index, value ->
            compose.onAllNodes(hasSetTextAction())[index].assertTextEquals(value)
        }
        compose.onNodeWithText(string(selectedMeal.displayNameRes)).assertExists()
        compose.runOnIdle { assertEquals(emptyList<ManualSubmission>(), submissions) }
        compose.onNodeWithText(string(R.string.action_save)).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(ManualSubmission("Homemade lunch", 417, 23.5, 48.25, 14.75, 6.5, selectedMeal)),
                submissions
            )
        }
    }

    @Test
    fun photoReviewRestoresHelpAndUsesRetainedNoteAndProgressiveMeal() {
        val restoration = StateRestorationTester(compose)
        val photos = listOf(testPhoto(), testPhoto())
        val note = "Rice is 220g; use half the sauce"
        val submissions = mutableListOf<Pair<String?, Boolean>>()
        restoration.setContent {
            // The review takes its draft from the host so adding another camera photo
            // can remove and reopen the sheet without clearing these inputs.
            var retainedNote by rememberSaveable { mutableStateOf("") }
            var retainedProgressiveMeal by rememberSaveable { mutableStateOf(false) }
            FudAITheme {
                MultiPhotoCaptureSheet(
                    imageBytesList = photos,
                    addsFromLibrary = true,
                    onAddPhoto = {},
                    onRemove = {},
                    onAnalyze = { text, progressive -> submissions += text to progressive },
                    onDismiss = {},
                    note = retainedNote,
                    onNoteChange = { retainedNote = it },
                    progressiveMeal = retainedProgressiveMeal,
                    onProgressiveMealChange = { retainedProgressiveMeal = it }
                )
            }
        }

        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(note)
        closeSoftKeyboard()
        compose.onNodeWithContentDescription(string(R.string.progressive_meal_info_title))
            .performScrollTo()
            .performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(string(R.string.progressive_meal_info_title)).assertExists()
        compose.onNodeWithText(string(R.string.action_done)).performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().assertTextEquals(note)
        compose.onNode(isToggleable()).performScrollTo().assertIsOn()
        compose.runOnIdle { assertEquals(emptyList<Pair<String?, Boolean>>(), submissions) }
        compose.onNodeWithText(string(R.string.action_analyze)).performClick()
        compose.runOnIdle { assertEquals(listOf(note to true), submissions) }
    }

    @Test
    fun analysisReviewRestoresEditedNameAndServingBeforeLogging() {
        val restoration = StateRestorationTester(compose)
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as FudAIApp
        val analysis = FoodAnalysis(
            name = "Brown rice",
            calories = 120,
            protein = 3.0,
            carbs = 24.0,
            fat = 1.0,
            servingSizeGrams = 100.0,
            servingUnitOptions = listOf(ServingUnitOption(unit = "cup", gramsPerUnit = 200.0))
        )
        var submission: ReviewSubmission? = null
        restoration.setContent {
            FudAITheme {
                FoodResultSheet(
                    analysis = analysis,
                    preferGramsByDefault = true,
                    container = app.container,
                    analyzeIngredientText = { error("No analysis should run while editing a draft") },
                    lookupIngredientBarcode = { error("No barcode lookup should run while editing a draft") },
                    analyzeIngredientImage = { error("No image analysis should run while editing a draft") },
                    onSave = { name, grams, known, scale, _, _, quantity, edited ->
                        submission = ReviewSubmission(name, grams, known, scale, quantity, edited)
                    },
                    onDismiss = {}
                )
            }
        }

        val nameField = hasSetTextAction() and hasText(analysis.name)
        val quantityField = hasSetTextAction() and hasText("100")
        val list = SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)
        compose.onNode(list).performScrollToNode(nameField)
        compose.onNode(nameField).performTextReplacement("Rice with lunch")
        closeSoftKeyboard()
        compose.onNode(list).performScrollToNode(quantityField)
        compose.onNode(quantityField).performTextReplacement("175")
        closeSoftKeyboard()

        restoration.emulateSavedInstanceStateRestore()

        val restoredName = hasSetTextAction() and hasText("Rice with lunch")
        val restoredQuantity = hasSetTextAction() and hasText("175")
        compose.onNode(list).performScrollToNode(restoredName)
        compose.onNode(restoredName).assertTextEquals("Rice with lunch")
        compose.onNode(list).performScrollToNode(restoredQuantity)
        compose.onNode(restoredQuantity).assertTextEquals("175")
        compose.runOnIdle { assertNull(submission) }
        compose.onNodeWithText(string(R.string.action_log)).performClick()
        compose.runOnIdle {
            assertEquals("Rice with lunch", submission?.name)
            assertEquals(175.0, submission?.grams)
            assertEquals(true, submission?.servingSizeIsKnown)
            assertEquals(1.75, submission?.scale)
            assertEquals(175.0, submission?.quantity)
            assertEquals(analysis.calories, submission?.editedAnalysis?.calories)
        }
    }

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun testPhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class ManualSubmission(
        val name: String,
        val calories: Int,
        val protein: Double,
        val carbs: Double,
        val fat: Double,
        val fiber: Double?,
        val meal: MealType
    )

    private data class ReviewSubmission(
        val name: String,
        val grams: Double,
        val servingSizeIsKnown: Boolean,
        val scale: Double,
        val quantity: Double?,
        val editedAnalysis: FoodAnalysis
    )
}
