package com.apoorvdarshan.calorietracker

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodLogMethod
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.HomeTopNutrient
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.models.WaterUnit
import com.apoorvdarshan.calorietracker.services.MealShare
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceEntryPoints
import com.apoorvdarshan.calorietracker.services.voice.VoiceMascot
import com.apoorvdarshan.calorietracker.services.voice.VoiceCameraHandle
import com.apoorvdarshan.calorietracker.services.voice.VoiceCameraMode
import com.apoorvdarshan.calorietracker.services.voice.BarcodeOutcome
import com.apoorvdarshan.calorietracker.services.voice.PhotoOutcome
import com.apoorvdarshan.calorietracker.services.voice.CameraOutcome
import com.apoorvdarshan.calorietracker.testing.FudAITestState
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * Drives the production MainActivity, navigation, screens and persistent repositories.
 * Only initial app data and background integrations are isolated by the test application.
 * Recreation/relaunch below means an Activity restart, not Android process death.
 */
@RunWith(AndroidJUnit4::class)
class AppFlowsTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    private var activity: ActivityScenario<MainActivity>? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = (context.applicationContext as FudAIApp).container.prefs

    // Capture while the failed screen still exists, before closing its Activity.
    @get:Rule(order = 1)
    val failureArtifacts = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching {
                val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val name = "${description.methodName}-${System.currentTimeMillis()}"
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(directory, "$name.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                File(directory, "$name.txt").writeText(
                    compose.onRoot(useUnmergedTree = true).printToString()
                )
            }
        }

        override fun finished(description: Description) {
            activity?.close()
            activity = null
        }
    }

    @Test
    fun homeViewMoreChangesDaysOnSwipesAndOpensOnlyOnTap() {
        launchFresh()
        val diary = compose.onNodeWithTag("home.diary")
        val viewMore = compose.onNodeWithTag("home.viewMore")
        val todayLabel = hasTestTag("home.dateHeading") and hasText(string(R.string.widget_today))
        val details = hasText(string(R.string.nutrition_details_title))
        diary.performScrollToNode(hasTestTag("home.viewMore"))
        viewMore.performTouchInput { swipe(center, center + Offset(30.dp.toPx(), 0f)) }
        compose.onNode(details).assertDoesNotExist()
        diary.performScrollToIndex(0)
        compose.onNode(todayLabel).assertIsDisplayed()
        diary.performScrollToNode(hasTestTag("home.viewMore"))
        viewMore.assertIsDisplayed().performTouchInput { swipeRight() }
        compose.onNode(details).assertDoesNotExist()
        diary.performScrollToIndex(0)
        waitFor(hasTestTag("home.dateHeading") and !hasText(string(R.string.widget_today)))
        diary.performScrollToNode(hasTestTag("home.viewMore"))
        viewMore.performTouchInput { swipeLeft() }
        compose.onNode(details).assertDoesNotExist()
        diary.performScrollToIndex(0)
        waitFor(todayLabel)
        diary.performScrollToNode(hasTestTag("home.viewMore"))
        viewMore.performTouchInput { swipeLeft() }
        compose.onNode(details).assertDoesNotExist()
        diary.performScrollToIndex(0)
        compose.onNode(todayLabel).assertIsDisplayed()
        diary.performScrollToNode(hasTestTag("home.viewMore"))
        viewMore.performTouchInput { click(center) }
        waitFor(details)
        compose.onNode(details).assertIsDisplayed()
    }

    @Test
    fun nutritionAndFavoritesSheetsDoNotOutlineTheScreenBehindThem() {
        FudAITestState.reset()
        runBlocking { prefs.setAppearanceMode("dark") }
        launchActivity()
        waitForHome()
        compose.onNodeWithTag("home.diary").performScrollToNode(hasTestTag("home.viewMore"))
        compose.onNodeWithTag("home.viewMore").performClick()
        val nutritionTitle = hasText(string(R.string.nutrition_details_title))
        waitFor(nutritionTitle)
        assertNoStrandedSheetOutline()
        pressBack()
        waitForAbsent(nutritionTitle)

        compose.onNodeWithTag("home.add").performClick()
        clickText(R.string.home_menu_reuse_meal)
        clickText(R.string.saved_meals_tab_favorites)
        waitFor(hasText(string(R.string.saved_meals_search_placeholder)))
        assertNoStrandedSheetOutline()
    }

    @Test
    fun mealDictationSheetDoesNotOutlineTheScreenBehindIt() {
        FudAITestState.reset()
        runBlocking {
            prefs.setAppearanceMode("dark")
            prefs.setSelectedSpeechProvider(SpeechProvider.GEMINI)
        }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        launchActivity()
        waitForHome()
        compose.onNodeWithTag("home.foodShortcut.2").performClick()
        val provider = hasText(string(SpeechProvider.GEMINI.displayNameRes))
        waitFor(provider)
        assertNoStrandedSheetOutline()
        // Dismiss the local recording without submitting it to a provider.
        pressBack()
        waitForAbsent(provider)
    }

    private fun assertNoStrandedSheetOutline() {
        compose.waitForIdle()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            // The misplaced frame was a continuous white stripe along the window
            // edges. Sample below the status bar, away from legitimate card borders.
            val rows = (screenshot.height / 12)..(screenshot.height * 5 / 12)
            val edges = (0..3) + (screenshot.width - 4 until screenshot.width)
            for (x in edges) {
                val white = rows.count { y ->
                    val pixel = screenshot.getPixel(x, y)
                    ((pixel shr 16) and 255) > 210 &&
                        ((pixel shr 8) and 255) > 210 && (pixel and 255) > 210
                }
                assertTrue("A white outline is stranded at window edge x=$x", white < rows.count() / 3)
            }
        } finally {
            screenshot.recycle()
        }
    }

    @Test
    fun homeCalendarOpensAsAPopup() {
        launchFresh()
        compose.onNodeWithTag("home.calendar").performClick()
        compose.onNodeWithTag("home.calendarPopup").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("home.calendarPopup").assertDoesNotExist()
        compose.onNodeWithTag("home.dateHeading").assertTextEquals(string(R.string.widget_today))
    }

    @Test
    fun hapticsAreOnByDefaultAndCanBeTurnedOff() {
        launchFresh()
        openTab("settings")
        val appSettings = compose.onNodeWithTag("settings.category.APP_PREFERENCES")
        appSettings.performScrollTo().performClick()
        val toggle = compose.onNodeWithTag("settings.haptics")
        toggle.performScrollTo().assertIsOn().performClick()
        awaitPreference(prefs.hapticsEnabled) { !it }
        relaunchActivity()
        openTab("settings")
        appSettings.performScrollTo().performClick()
        toggle.performScrollTo().assertIsOff().performClick()
        awaitPreference(prefs.hapticsEnabled) { it }
    }

    @Test
    fun byokRailShowsPlansEvenWhenBillingIsUnavailable() {
        launchFresh()
        val plans = compose.onNodeWithTag("nav.upgrade")
        val voice = compose.onNodeWithTag("nav.voice")
        plans.assert(hasAnyAncestor(hasScrollAction()))
        voice.assert(!hasAnyAncestor(hasScrollAction()))
        assertTrue(plans.performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom <=
            voice.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top)

        plans.performClick()
        val unavailable = hasText(string(R.string.hosted_plans_unavailable_title))
        waitFor(unavailable)
        compose.onNode(unavailable).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.hosted_close)).performClick()
        waitForAbsent(unavailable)
        plans.assertIsDisplayed()

        runBlocking { prefs.setAiAccessMode(AiAccessMode.HOSTED) }
        waitForAbsent(hasTestTag("nav.upgrade"))
        voice.assertIsDisplayed()
    }

    @Test
    fun aboutAndSupportHaveSeparateRailDestinations() {
        launchFresh()
        openTab("settings")
        compose.onNodeWithTag("settings.category.APP_UPDATES").assertDoesNotExist()
        compose.onNodeWithTag("settings.category.SUPPORT").assertDoesNotExist()

        openTab("about")
        for (category in listOf("APP_UPDATES", "HELP_FEEDBACK", "COMMUNITY", "LEGAL")) {
            compose.onNodeWithTag("settings.category.$category").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("settings.category.LEGAL").performClick()
        compose.onNodeWithTag("nav.about").assertIsSelected()
        compose.onNodeWithTag("about.legal.duns").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.about_duns)).assertIsDisplayed()
        compose.onNodeWithText("311099925").assertIsDisplayed()
        openTab("about")
        compose.onNodeWithTag("settings.category.LEGAL").assertExists()

        openTab("support")
        compose.onNodeWithText(string(R.string.about_rate)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nav.voice").assertIsDisplayed()
    }

    @Test
    fun homeFoodShortcutsAreSquareAndCustomizable() {
        launchFresh()
        val first = compose.onNodeWithTag("home.foodShortcut.0")
        val second = compose.onNodeWithTag("home.foodShortcut.1")
        val firstBounds = first.assertContentDescriptionEquals(string(FoodLogMethod.CAMERA.titleRes))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val secondBounds = second.assertContentDescriptionEquals(string(FoodLogMethod.BARCODE.titleRes))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.width, firstBounds.height, 1f)
        assertEquals(firstBounds.width, secondBounds.width, 1f)
        assertEquals(firstBounds.height, secondBounds.height, 1f)
        assertTrue(secondBounds.right < compose.onNodeWithTag("home.add").fetchSemanticsNode().boundsInRoot.left)

        openTab("settings")
        compose.onNodeWithTag("settings.category.APP_PREFERENCES").performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.settings_add_menu_title)).performScrollTo().performClick()
        val picker = hasTestTag("settings.homeShortcut.0")
        waitFor(picker)
        compose.onNode(picker).performClick()
        val manualOption = hasText(string(R.string.home_menu_manual_entry)) and hasClickAction()
        waitFor(manualOption)
        compose.onNode(manualOption).performClick()
        awaitPreference(prefs.homeFoodShortcuts) { it.methods.first() == FoodLogMethod.MANUAL }

        relaunchActivity()
        first.assertContentDescriptionEquals(string(FoodLogMethod.MANUAL.titleRes)).performClick()
        second.assertContentDescriptionEquals(string(FoodLogMethod.BARCODE.titleRes))
        waitFor(hasTestTag("manual.name"))
    }

    @Test
    fun voiceMascotGallerySelectsThePinnedCharacter() {
        launchFresh()
        openVoiceSettings()
        for (mascot in VoiceMascot.entries) {
            compose.onNodeWithTag("settings.voiceMascot.gallery.${mascot.name}").assertIsDisplayed()
        }
        val brulee = compose.onNodeWithTag("settings.voiceMascot.gallery.BRULEE")
        val jam = compose.onNodeWithTag("settings.voiceMascot.gallery.JAM")
        val shortcut = compose.onNodeWithTag("nav.voice")
        brulee.assertIsSelected()
        shortcut.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Brulee"))
        jam.performClick()
        awaitPreference(prefs.voiceMascotPreferences) { it.selected == VoiceMascot.JAM }
        jam.assertIsSelected()
        brulee.assertIsNotSelected()
        shortcut.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Jam"))
        compose.onNodeWithText(string(R.string.settings_meal_times_customize)).performScrollTo().performClick()
        waitFor(hasTestTag("settings.voiceMascot.name"))
        compose.onNodeWithTag("settings.voiceMascot.color").assertExists()
        compose.onNodeWithTag("settings.voiceMascot.personality").assertExists()
        relaunchActivity()
        shortcut.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Jam"))
    }

    @Test
    fun pinnedVoiceExplainsAccessWithoutLeavingTheCurrentTab() {
        launchFresh()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        openTab("coach")
        val voice = compose.onNodeWithTag("nav.voice")
        voice.assertIsDisplayed().performClick()
        waitFor(hasText(string(R.string.voice_notice_setup_title)))
        compose.onNodeWithText(string(R.string.voice_action_see_plans)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_action_add_key)).assertIsDisplayed()
        compose.onNodeWithTag("voiceAssistantClose").performClick()
        waitForAbsent(hasTestTag("voiceAssistantOverlay"))
        compose.onNodeWithTag("nav.coach").assertIsSelected()
        voice.assertIsDisplayed()
    }

    @Test
    fun coachOpensAtTheNewestMessageAndKeepsHistoryReadable() {
        FudAITestState.reset(onboarded = true)
        val messages = (1..40).map { index ->
            ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "History message $index")
        }
        val repository = (context.applicationContext as FudAIApp).container.chatRepository
        runBlocking { repository.replaceAll(messages) }
        launchActivity()
        waitForHome()
        openTab("coach")

        val latest = "History message 40"
        val older = "History message 39"
        val oldest = "History message 1"
        waitFor(hasText(latest))
        val latestBounds = compose.onNodeWithText(latest).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val olderBounds = compose.onNodeWithText(older).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(latestBounds.top > olderBounds.top)
        compose.onNodeWithText(oldest).assertIsNotDisplayed()

        compose.onNodeWithTag("coach.messages").performScrollToIndex(messages.size)
        compose.onNodeWithText(oldest).assertIsDisplayed()
        runBlocking { repository.append(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "New reply")) }
        compose.onNodeWithText(oldest).assertIsDisplayed()
        compose.onNodeWithText("New reply").assertIsNotDisplayed()

        openTab("home")
        openTab("coach")
        waitFor(hasText("New reply"))
        compose.onNodeWithText("New reply").assertIsDisplayed()
        runBlocking { repository.append(ChatMessage(role = ChatMessage.Role.USER, content = "Newest message")) }
        waitFor(hasText("Newest message"))
        compose.onNodeWithText("Newest message").assertIsDisplayed()
    }

    @Test
    fun coachKeyboardKeepsTheRailInPlaceAndAllowsSwitchingTabs() {
        launchFresh()
        openTab("coach")
        val home = compose.onNodeWithTag("nav.home")
        val initialLeft = home.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left
        val voice = compose.onNodeWithTag("nav.voice")
        val initialVoiceLeft = voice.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left
        val plans = compose.onNodeWithTag("nav.upgrade")
        plans.assertExists()
        val imeVisible = AtomicReference(false)
        compose.onNodeWithTag("coach.input").performClick()
        compose.waitUntil(timeoutMillis = 8_000) {
            activity!!.onActivity { main ->
                imeVisible.set(
                    ViewCompat.getRootWindowInsets(main.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                )
            }
            imeVisible.get()
        }
        assertEquals(initialLeft, home.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left, 1f)
        assertEquals(initialVoiceLeft, voice.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left, 1f)
        plans.performScrollTo().assertIsDisplayed()
        assertEquals(initialVoiceLeft, voice.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left, 1f)
        home.performScrollTo()
        home.performClick()
        waitForHome()
        home.assertIsSelected()
    }

    @Test
    fun progressDateRangeUsesOneAnchoredControlAndRetainsItsSelection() {
        launchFresh()
        openTab("progress")
        val scroll = compose.onNodeWithTag("progress.content")
        val selector = hasTestTag("progress.timeRange")
        val week = string(R.string.progress_range_week)
        val month = string(R.string.progress_range_month)
        compose.onNodeWithText(string(R.string.weekly_recap_title)).assertDoesNotExist()
        compose.onNode(selector and hasText(week)).assertIsDisplayed().performClick()
        compose.onNodeWithText(month).assertIsDisplayed().performClick()
        compose.onNode(selector and hasText(month)).assertIsDisplayed()

        openTab("weeklyChallenge")
        waitFor(hasText(string(R.string.challenge_title)))
        compose.onNodeWithTag("nav.progress").assertIsNotSelected()
        compose.onNodeWithTag("nav.voice").assertIsDisplayed()
        compose.onNodeWithTag("progress.timeRange").assertDoesNotExist()
        openTab("home")
        openTab("progress")
        scroll.performScrollToNode(selector)
        compose.onNode(selector and hasText(month)).assertIsDisplayed()
    }

    @Test
    fun freshInstallShowsOnboardingAndAdvancesThroughProfileSteps() {
        launchFresh(onboarded = false)
        waitFor(hasTestTag("onboarding.WELCOME"))
        compose.onNodeWithTag("nav.home").assertDoesNotExist()
        compose.onNodeWithTag("nav.upgrade").assertDoesNotExist()

        clickText(R.string.action_get_started)
        waitFor(hasTestTag("onboarding.GENDER"))
        clickText(R.string.gender_female)
        clickText(R.string.action_continue)
        waitFor(hasTestTag("onboarding.BIRTHDAY"))
        compose.onNodeWithText(string(R.string.onboarding_birthday_title)).assertIsDisplayed()
        clickText(R.string.action_continue)
        waitFor(hasTestTag("onboarding.HEIGHT_WEIGHT"))
        compose.onNodeWithText(string(R.string.onboarding_height_weight_title)).assertIsDisplayed()
        compose.onNodeWithTag("nav.home").assertDoesNotExist()
        // The test deliberately stops before provider setup and permission prompts.
    }

    @Test
    fun returningUserScreenOpensFromWelcomeAndStartFreshContinuesTheNormalOnboarding() {
        launchFresh(onboarded = false)
        waitFor(hasTestTag("onboarding.WELCOME"))
        // Background services are off here, so Welcome back never opens on its own.
        compose.onNodeWithTag("onboarding.RETURNING").assertDoesNotExist()

        clickText(R.string.returning_entry)
        waitFor(hasTestTag("onboarding.RETURNING"))
        compose.onNodeWithText(string(R.string.returning_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.action_get_started)).assertDoesNotExist()

        // System back returns to the welcome content instead of leaving the app.
        pressBack()
        waitForAbsent(hasTestTag("onboarding.RETURNING"))
        waitFor(hasText(string(R.string.action_get_started)))
        compose.onNodeWithTag("onboarding.WELCOME").assertExists()

        // Cards only toggle; Continue (never tapped here) is what opens Google or Health
        // Connect, so no sheet appears in this test.
        clickText(R.string.returning_entry)
        waitFor(hasTestTag("onboarding.RETURNING"))
        val continueButton = hasText(string(R.string.action_continue)) and hasClickAction()
        compose.onNode(continueButton).assertIsNotEnabled()
        val driveCard = hasText(string(R.string.returning_drive_title)) and isToggleable()
        compose.onNode(driveCard).assertIsOff().performClick()
        compose.onNode(driveCard).assertIsOn()
        compose.onNode(continueButton).assertIsEnabled()
        compose.onNode(driveCard).performClick()
        compose.onNode(driveCard).assertIsOff()
        compose.onNode(continueButton).assertIsNotEnabled()

        clickText(R.string.returning_start_fresh)
        waitFor(hasTestTag("onboarding.GENDER"))
        compose.onNodeWithTag("onboarding.RETURNING").assertDoesNotExist()
        compose.onNodeWithTag("nav.home").assertDoesNotExist()
        runBlocking { assertTrue(!prefs.cloudBackupEnabled.first()) }
    }

    @Test
    fun completedOnboardingOpensHomeAndEveryMainTabNavigates() {
        launchFresh()
        compose.onNodeWithTag("nav.home").assertIsSelected()
        compose.onNodeWithTag("home.add").assertIsDisplayed()
        val addBounds = compose.onNodeWithTag("home.add").fetchSemanticsNode().boundsInRoot
        val voiceBounds = compose.onNodeWithTag("nav.voice").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Voice stays in the right rail", addBounds.right <= voiceBounds.left)
        compose.onNodeWithTag("voiceAssistantButton").assertDoesNotExist()
        compose.onNodeWithTag("nav.camera").assertDoesNotExist()
        assertTrue("The diary cannot scroll behind the pinned controls",
            compose.onNodeWithTag("home.diary").fetchSemanticsNode().boundsInRoot.bottom <= addBounds.top)
        assertTrue("The right rail stays beside the action row", addBounds.right <=
            compose.onNodeWithTag("nav.home").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithTag("home.calendar").assertIsDisplayed()
        compose.onNodeWithTag("home.calorieSegments").assertIsDisplayed()
        for (tab in listOf("progress", "weeklyChallenge", "coach", "settings", "workouts", "exerciseLibrary", "home")) {
            openTab(tab)
            compose.onNodeWithTag("nav.$tab").assertIsSelected()
            if (tab == "workouts") {
                waitFor(hasTestTag("workouts.add"))
                val voiceEntry = compose.onNodeWithTag("workouts.shortcut.0")
                    .assertContentDescriptionEquals(string(R.string.workout_text_voice))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val saved = compose.onNodeWithTag("workouts.shortcut.1")
                    .assertContentDescriptionEquals(string(R.string.workout_source_saved))
                    .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(voiceEntry.width, voiceEntry.height, 1f)
                assertEquals(voiceEntry.width, saved.width, 1f)
                compose.onNodeWithTag("voiceAssistantButton").assertDoesNotExist()
                compose.onNodeWithTag("nav.voice").assertIsDisplayed()
                val workoutAdd = compose.onNodeWithTag("workouts.add")
                    .assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                activity!!.onActivity { main ->
                    val decor = main.window.decorView
                    val navigation = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                        .getInsets(WindowInsetsCompat.Type.navigationBars())
                    assertTrue("The workout dock stays above the system navigation bar",
                        workoutAdd.bottom <= decor.height - navigation.bottom)
                }
            }
        }
        compose.onNodeWithTag("home.add").assertIsDisplayed()
        compose.onNodeWithTag("onboarding.WELCOME").assertDoesNotExist()
    }

    @Test
    fun startupRailUsesPersistedVisibilityAndMascot() {
        FudAITestState.reset()
        runBlocking {
            prefs.setTabHidden("progress", true)
            prefs.selectVoiceMascot(VoiceMascot.JAM)
        }
        launchActivity()
        waitForHome()
        compose.onNodeWithTag("nav.progress").assertDoesNotExist()
        compose.onNodeWithTag("nav.weeklyChallenge").assertDoesNotExist()
        compose.onNodeWithTag("nav.voice").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Jam")
        )

        runBlocking { prefs.setHideVoiceShortcut(true) }
        recreateActivity()
        waitForHome()
        compose.onNodeWithTag("nav.progress").assertDoesNotExist()
        compose.onNodeWithTag("nav.voice").assertDoesNotExist()
    }

    @Test
    fun startupUsesPersistedEnergyUnitAndGoal() {
        FudAITestState.reset()
        val target = 2_137
        runBlocking {
            prefs.setEnergyUnit(EnergyUnit.KJ)
            prefs.updateUserProfile { it.copy(customCalories = target) }
        }
        val goal = hasText(string(EnergyUnit.KJ.valueFormatRes, EnergyUnit.KJ.displayValue(target)))
        launchActivity()
        waitForHome()
        waitFor(goal)
        compose.onNode(goal).assertIsDisplayed()
        recreateActivity()
        waitForHome()
        compose.onNode(goal).assertIsDisplayed()
    }

    @Test
    fun completedOnboardingWithoutSavedProfileDoesNotTrapTheSplash() {
        FudAITestState.reset(onboarded = false)
        runBlocking { prefs.setOnboardingCompleted(true) }

        launchActivity()
        waitForHome()
        compose.onNodeWithTag("home.add").assertIsDisplayed()
    }

    @Test
    fun minimizingVoiceClosesCameraAndCancelsBarcodeWaitWithoutStartingAudio() {
        FudAITestState.reset()
        val controller = (context.applicationContext as FudAIApp).container.voiceAssistant
        // Exercise the UI bridge without an overlay, permissions, sockets or camera hardware.
        runBlocking(Dispatchers.Main) {
            controller.requestStart()
            try {
                val scan = async(start = CoroutineStart.UNDISPATCHED) { controller.scanBarcode() }
                assertEquals(VoiceCameraMode.BARCODE, controller.cameraMode())
                val camera = object : VoiceCameraHandle {
                    override fun latestFrame() = null
                    override suspend fun takePhoto() = byteArrayOf(1)
                }
                controller.cameraReady(camera)
                yield()
                controller.collapse()
                assertEquals(BarcodeOutcome.Cancelled, scan.await())
                assertEquals(null, controller.cameraMode())
                controller.cameraReady(camera) // A late CameraX callback cannot reopen a hidden camera.
                assertEquals(PhotoOutcome.NotOpen, controller.takePhoto())
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun voicePhotoCaptureReleasesCameraOnSuccessAndFailureWithoutEndingConversation() {
        FudAITestState.reset()
        val controller = (context.applicationContext as FudAIApp).container.voiceAssistant
        runBlocking(Dispatchers.Main) {
            controller.requestStart()
            try {
                for (image in listOf(byteArrayOf(1, 2, 3), null)) {
                    val camera = object : VoiceCameraHandle {
                        override fun latestFrame() = null
                        override suspend fun takePhoto() = image
                    }
                    val opening = async(start = CoroutineStart.UNDISPATCHED) { controller.openCamera(VoiceCameraMode.FOOD) }
                    controller.cameraReady(camera)
                    assertEquals(CameraOutcome.Opened, opening.await())
                    val result = controller.takePhoto()
                    if (image == null) assertEquals(PhotoOutcome.Failed, result)
                    else assertTrue(result is PhotoOutcome.Taken && result.jpeg.contentEquals(image))
                    assertEquals(null, controller.cameraMode())
                    assertTrue(controller.isActive)
                }
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun cancelledVoiceCaptureCannotCloseReplacementAndPhotoPickerClosesCamera() {
        FudAITestState.reset()
        val controller = (context.applicationContext as FudAIApp).container.voiceAssistant
        val captured = CompletableDeferred<ByteArray?>()
        runBlocking(Dispatchers.Main) {
            controller.requestStart()
            try {
                val oldCamera = object : VoiceCameraHandle {
                    override fun latestFrame() = null
                    override suspend fun takePhoto() = withContext(NonCancellable) { captured.await() }
                }
                val opening = async(start = CoroutineStart.UNDISPATCHED) { controller.openCamera(VoiceCameraMode.FOOD) }
                controller.cameraReady(oldCamera)
                assertEquals(CameraOutcome.Opened, opening.await())
                val capture = async(start = CoroutineStart.UNDISPATCHED) { controller.takePhoto() }
                capture.cancel()
                controller.closeCamera()
                val replacement = object : VoiceCameraHandle {
                    override fun latestFrame() = null
                    override suspend fun takePhoto() = byteArrayOf(4, 5, 6)
                }
                val reopening = async(start = CoroutineStart.UNDISPATCHED) { controller.openCamera(VoiceCameraMode.FOOD) }
                controller.cameraReady(replacement)
                assertEquals(CameraOutcome.Opened, reopening.await())
                captured.complete(byteArrayOf(1))
                capture.join()
                assertEquals(VoiceCameraMode.FOOD, controller.cameraMode())

                val picker = async(start = CoroutineStart.UNDISPATCHED) { controller.pickPhotos(1) }
                assertEquals(null, controller.cameraMode())
                picker.cancel()
                picker.join()
                assertTrue(controller.isActive)
            } finally {
                captured.complete(null)
                controller.close()
            }
        }
    }

    @Test
    fun externalVoiceLaunchRemainsPendingDuringOnboardingAndRecreation() {
        FudAITestState.reset(onboarded = false)
        // The extra also requests voice. Keep the action stable because ActivityScenario matches
        // lifecycle events by intent, while MainActivity consumes the TALK action during onCreate.
        launchActivity(VoiceEntryPoints.launchIntent(context).setAction(null))
        waitFor(hasTestTag("onboarding.WELCOME"))
        compose.waitForIdle()
        val saved = Bundle()
        activity!!.onActivity { instrumentation.callActivityOnSaveInstanceState(it, saved) }
        assertTrue(saved.containsKey("pendingVoiceLaunch"))
        assertTrue(!(context.applicationContext as FudAIApp).container.voiceAssistant.isActive)

        recreateActivity()
        waitFor(hasTestTag("onboarding.WELCOME"))
        val restored = Bundle()
        activity!!.onActivity { instrumentation.callActivityOnSaveInstanceState(it, restored) }
        assertEquals(saved.getLong("pendingVoiceLaunch"), restored.getLong("pendingVoiceLaunch"))
    }

    @Test
    fun voiceAssistantSettingsPersistWithoutStartingAMicrophoneSession() {
        launchFresh()
        openVoiceSettings()
        compose.onNodeWithTag("voiceSettings.confirmSaves").performScrollTo().assertIsOn().performClick()
        awaitPreference(prefs.voiceAssistantPreferences) { !it.confirmSaves }
        val saveToCoach = compose.onNodeWithTag("voiceSettings.saveToCoach").performScrollTo().assertIsOn()
        assertTrue("The switch must stay to the left of the rail",
            saveToCoach.fetchSemanticsNode().boundsInRoot.right <=
                compose.onNodeWithTag("nav.workouts").fetchSemanticsNode().boundsInRoot.left)
        saveToCoach.performClick()
        awaitPreference(prefs.voiceAssistantPreferences) { !it.saveToCoach }
        compose.onNodeWithTag("voiceSettings.autoCapture").performScrollTo().assertIsOn().performClick()
        awaitPreference(prefs.voiceAssistantPreferences) { !it.autoCapture }

        recreateActivity()
        openVoiceSettings()
        compose.onNodeWithTag("voiceSettings.autoCapture").performScrollTo().assertIsOff()
        compose.onNodeWithTag("voiceSettings.confirmSaves").performScrollTo().assertIsOff()
        compose.onNodeWithTag("voiceSettings.saveToCoach").performScrollTo().assertIsOff()
        assertTrue(!(context.applicationContext as FudAIApp).container.voiceAssistant.isActive)
    }

    @Test
    fun manualFoodRequiresNameAndCaloriesAndPersistsAcrossActivityRestarts() {
        launchFresh()
        openManualEntry()
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.name").performTextReplacement("CI oat bowl")
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.calories").performTextReplacement("321")
        compose.onNodeWithTag("manual.name").performTextClearance()
        compose.onNodeWithTag("manual.save").assertIsNotEnabled()
        compose.onNodeWithTag("manual.name").performTextReplacement("CI oat bowl")
        closeSoftKeyboard()
        compose.onNodeWithTag("manual.save").assertIsEnabled().performClick()
        waitForAbsent(hasTestTag("manual.name"))

        val saved = awaitPreference(prefs.foodEntries) { it.size == 1 }.single()
        assertEquals("CI oat bowl", saved.name)
        assertEquals(321, saved.calories)
        assertFoodVisible(saved.name)
        compose.onNodeWithTag("home.diary").performTouchInput {
            swipeUp(startY = height * 0.55f, endY = height * 0.15f)
        }
        val addBounds = compose.onNodeWithTag("home.add").fetchSemanticsNode().boundsInRoot
        val cardBounds = compose.onNodeWithTag("diary.food.${saved.id}", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("The final meal card can scroll completely above Log Food", cardBounds.bottom <= addBounds.top)
        val titleBounds = compose.onNodeWithTag("home.mealHeader.${saved.mealType.name}.title").fetchSemanticsNode().boundsInRoot
        val iconBounds = compose.onNodeWithTag("home.mealHeader.${saved.mealType.name}.icon").fetchSemanticsNode().boundsInRoot
        val totalsBounds = compose.onNodeWithTag("home.mealHeader.${saved.mealType.name}.totals").fetchSemanticsNode().boundsInRoot
        assertEquals("Meal totals align with the leading edge of the header", iconBounds.left, totalsBounds.left, 1f)
        assertTrue("Meal totals sit below the title", totalsBounds.top >= titleBounds.bottom)
        assertTrue("Meal totals stay clear of the pinned dock", totalsBounds.bottom <= addBounds.top)

        recreateActivity()
        waitForHome()
        assertFoodVisible(saved.name)
        relaunchActivity()
        assertFoodVisible(saved.name)
        runBlocking {
            val restored = prefs.foodEntries.first().single()
            assertEquals(saved.id, restored.id)
            assertEquals(321, restored.calories)
        }
    }

    @Test
    fun cancellingManualEntryDoesNotWriteToDiary() {
        launchFresh()
        openManualEntry()
        compose.onNodeWithTag("manual.name").performTextReplacement("Do not save this meal")
        compose.onNodeWithTag("manual.calories").performTextReplacement("200")
        closeSoftKeyboard()
        clickText(R.string.action_cancel)
        waitForAbsent(hasTestTag("manual.name"))
        assertEmptyDiary()
        relaunchActivity()
        assertEmptyDiary()
        runBlocking { assertTrue(prefs.foodEntries.first().isEmpty()) }
    }

    @Test
    fun waterLogAndConfirmedDeletionPersistAcrossActivityRelaunch() {
        launchFresh()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff().performClick()
        waitForWaterSetting(true)
        openTab("home")

        compose.onNodeWithTag("home.add").performClick()
        val waterMenuItem = hasText(string(R.string.water)) and hasClickAction()
        waitFor(waterMenuItem)
        compose.onNode(waterMenuItem).performClick()
        // The smallest default quick-add amount; the logged row shows the same text,
        // so wait for the menu itself to close.
        val oneGlass = WaterUnit.MILLILITERS.format(250)
        val customAmount = string(R.string.water_custom_amount)
        waitFor(hasText(oneGlass))
        compose.onNodeWithText(oneGlass).performClick()
        waitForAbsent(hasText(customAmount))
        val entry = awaitPreference(prefs.waterEntries) { it.size == 1 }.single()
        assertEquals(250, entry.milliliters)
        val row = "diary.water.${entry.id}"
        showDiaryRow(hasTestTag(row))
        compose.onNodeWithTag(row).assertIsDisplayed()

        relaunchActivity()
        showDiaryRow(hasTestTag(row))
        compose.onNodeWithTag(row).assertIsDisplayed().performTouchInput { swipeLeft() }
        waitFor(hasText("Delete Water Log?"))
        clickText(R.string.action_delete)
        waitForAbsent(hasText("Delete Water Log?"))
        awaitPreference(prefs.waterEntries) { it.isEmpty() }
        assertEmptyDiary()
        relaunchActivity()
        assertEmptyDiary()
        compose.onNodeWithTag(row).assertDoesNotExist()
        runBlocking { assertTrue(prefs.waterEntries.first().isEmpty()) }
    }

    @Test
    fun waterTrackingSettingPersistsOnAndOffAcrossActivityRestarts() {
        launchFresh()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff().performClick()
        waitForWaterSetting(true)

        recreateActivity()
        // Settings saves its selected category in the real navigation state.
        openTab("home")
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOn()
        relaunchActivity()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOn().performClick()
        waitForWaterSetting(false)

        relaunchActivity()
        openWaterSettings()
        compose.onNodeWithTag("settings.water.enabled").assertIsOff()
        openTab("home")
        compose.onNodeWithTag("home.add").performClick()
        waitFor(hasText(string(R.string.home_menu_describe_meal)) and hasClickAction())
        compose.onNodeWithText(string(R.string.home_menu_describe_meal)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.water)).assertDoesNotExist()
    }

    @Test
    fun nutritionPickerSavesTheEditedCardsAndKeepsFixedWater() {
        FudAITestState.reset()
        runBlocking { prefs.setWaterTrackingEnabled(true) }
        launchActivity()
        waitForHome()

        val viewMore = string(R.string.home_view_more)
        waitFor(hasText(viewMore) and hasClickAction())
        compose.onNode(hasText(viewMore) and hasClickAction()).performClick()
        val cards = "nutrition.homeCards"
        waitFor(hasTestTag(cards))
        compose.onNodeWithTag(cards).performClick()
        waitFor(hasText(string(R.string.home_nutrients_water_fixed_short)))

        val fiber = compose.onNodeWithTag("nutrition.picker.FIBER")
        compose.onNodeWithTag("nutrition.picker.options").performScrollToKey("FIBER")
        fiber.assertIsOn().performClick()
        fiber.assertIsOff()
        compose.onNode(
            hasText(string(R.string.action_done)) and hasClickAction() and
                hasAnyAncestor(hasTestTag("nutrition.picker.actions"))
        ).performClick()
        waitForAbsent(hasTestTag("nutrition.picker.options"))

        val expected = HomeTopNutrient.DefaultSelection - HomeTopNutrient.FIBER
        awaitPreference(prefs.homeTopNutrients) { HomeTopNutrient.fromStorage(it) == expected }
        val summary = "nutrition.homeCards.summary"
        val expectedSummary = (expected.map { string(it.displayNameRes) } + string(R.string.water))
            .joinToString(", ")
        val updatedSummary = hasTestTag(summary) and hasText(expectedSummary)
        // Picker dismissal, disk persistence and the ViewModel's next UI
        // frame are separate events. Match the new text, not the old Water row.
        waitFor(updatedSummary, useUnmergedTree = true)
        compose.onNode(updatedSummary, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun sharedMealLinkSheetShowsTheChosenEnergyUnit() {
        FudAITestState.reset()
        runBlocking { prefs.setEnergyUnit(EnergyUnit.KJ) }
        val meals = listOf(
            FoodEntry(name = "Shared Oats", calories = 300, protein = 10.0, carbs = 50.0,
                fat = 5.0, source = FoodSource.MANUAL),
            FoodEntry(name = "Shared Eggs", calories = 124, protein = 10.0, carbs = 1.0,
                fat = 8.0, source = FoodSource.MANUAL)
        )
        launchActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(MealShare.link(meals)), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        // The import sheet is composed beside the NavHost, so it needs the root-level unit too.
        waitFor(hasText(string(R.string.kj_value_format, EnergyUnit.KJ.displayValue(300))))
        compose.onNodeWithText(string(R.string.kj_value_format, EnergyUnit.KJ.displayValue(124))).assertExists()
        compose.onNodeWithText(
            string(R.string.import_add_to_log_many_format_kj, 2, EnergyUnit.KJ.displayValue(424))
        ).assertExists()
        compose.onNodeWithText(string(R.string.kcal_value_format, 300)).assertDoesNotExist()
    }

    @Test
    fun trackingAndNotificationsShareOneSettingsPage() {
        launchFresh()
        openTab("settings")
        compose.onNodeWithTag("nav.settings").performClick()
        waitFor(hasTestTag("settings.category.TRACKING_NOTIFICATIONS"))
        // The former separate rows are gone (#432).
        compose.onNodeWithTag("settings.category.TRACKING_REMINDERS").assertDoesNotExist()
        compose.onNodeWithTag("settings.category.NOTIFICATIONS").assertDoesNotExist()
        compose.onNodeWithTag("settings.category.TRACKING_NOTIFICATIONS").performScrollTo().performClick()

        waitFor(hasTestTag("settings.water.enabled"))
        compose.onNodeWithText(string(R.string.settings_section_tracking_notifications)).assertIsDisplayed()
        // Tracking comes first, then the notification master switch.
        val water = compose.onNodeWithTag("settings.water.enabled").fetchSemanticsNode().boundsInRoot
        val notifications = compose.onNodeWithTag("settings.notifications.enabled").fetchSemanticsNode().boundsInRoot
        assertTrue(water.top < notifications.top)
    }

    @Test
    fun workoutRestTimerSelectsFromAnAnchoredPopup() {
        launchFresh()
        openTab("settings")
        val category = "settings.category.WORKOUT"
        waitFor(hasTestTag(category))
        compose.onNodeWithTag(category).performScrollTo().performClick()

        val timer = "settings.workout.restTimer"
        waitFor(hasTestTag(timer))
        compose.onNodeWithTag(timer).performScrollTo().performClick()
        val off = string(R.string.workout_rest_off)
        val popupOption = hasText(off) and hasAnyAncestor(isPopup())
        waitFor(popupOption)
        compose.onNode(popupOption).assertIsDisplayed().performClick()

        waitFor(hasTestTag(timer) and hasText(off))
        compose.onNode(isPopup()).assertDoesNotExist()
        val workouts = (context.applicationContext as FudAIApp).container.workoutRepository
        awaitPreference(workouts.preferences) { it.restTimerSeconds == 0 }
    }

    @Test
    fun workoutLibrarySearchOpensRealExerciseDetails() {
        launchFresh()
        openTab("workouts")
        waitFor(hasTestTag("workouts.add"))
        openTab("exerciseLibrary")
        compose.onNodeWithTag("nav.workouts").assertIsNotSelected()
        waitFor(hasTestTag("workouts.search"))
        compose.onNodeWithTag("workouts.search").performTextReplacement("Barbell Full Squat")
        closeSoftKeyboard()
        val result = hasText("Barbell Full Squat") and hasAnyAncestor(hasTestTag("workouts.results"))
        waitFor(result)
        compose.onNodeWithTag("workouts.results").performScrollToNode(result)
        compose.onNode(result).assertIsDisplayed().performClick()
        waitFor(hasTestTag("workouts.detail"))
        compose.onNodeWithText("Barbell Full Squat").assertIsDisplayed()
        openTab("workouts")
        waitFor(hasTestTag("workouts.add"))
        openTab("exerciseLibrary")
        waitFor(hasTestTag("workouts.detail"))
        compose.onNodeWithContentDescription(string(R.string.back)).performClick()
        waitFor(hasTestTag("workouts.search"))
        compose.onNodeWithTag("workouts.search").assertTextEquals("Barbell Full Squat")
    }

    private fun launchFresh(onboarded: Boolean = true) {
        FudAITestState.reset(onboarded = onboarded)
        launchActivity()
        if (onboarded) waitForHome()
    }

    private fun launchActivity(
        intent: Intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ) {
        activity = withActivityTransition { ActivityScenario.launch<MainActivity>(intent) }
    }

    private fun recreateActivity() = withActivityTransition { checkNotNull(activity).recreate() }

    private fun <T> withActivityTransition(action: () -> T): T {
        val transition = FutureTask(action)
        // ActivityScenario waits for the native splash to dismiss on launch and
        // recreation. Keep advancing Compose frames so the shell can release it.
        thread(name = "fud-test-activity-transition", isDaemon = true) { transition.run() }
        try {
            compose.waitUntil(timeoutMillis = 15_000) { transition.isDone }
            return transition.get()
        } finally {
            transition.cancel(true)
        }
    }

    private fun relaunchActivity() {
        activity!!.close()
        activity = null
        launchActivity()
        waitForHome()
    }

    private fun waitForHome() {
        waitFor(hasTestTag("home.add"))
        waitFor(hasTestTag("nav.home") and isSelected())
        compose.onNodeWithTag("nav.home").assertIsSelected()
    }

    private fun openTab(tab: String) {
        waitFor(hasTestTag("nav.$tab"))
        compose.onNodeWithTag("nav.$tab").performScrollTo().performClick()
        waitFor(hasTestTag("nav.$tab") and isSelected())
    }

    private fun openManualEntry() {
        compose.onNodeWithTag("home.add").performClick()
        clickText(R.string.home_menu_describe_meal)
        clickText(R.string.home_menu_manual_entry)
        waitFor(hasTestTag("manual.name"))
    }

    private fun openWaterSettings() {
        openTab("settings")
        // A restored Settings tab can still be inside its last category.
        // Re-tapping the real tab returns to the hub, just as a user would.
        compose.onNodeWithTag("nav.settings").performClick()
        waitFor(hasTestTag("settings.category.TRACKING_NOTIFICATIONS"))
        compose.onNodeWithTag("settings.category.TRACKING_NOTIFICATIONS").performScrollTo().performClick()
        waitFor(hasTestTag("settings.water.enabled"))
        compose.onNodeWithTag("settings.water.enabled").performScrollTo()
    }

    private fun openVoiceSettings() {
        openTab("settings")
        compose.onNodeWithTag("nav.settings").performClick()
        waitFor(hasTestTag("settings.category.VOICE_ASSISTANT"))
        compose.onNodeWithTag("settings.category.VOICE_ASSISTANT").performScrollTo().performClick()
        waitFor(hasTestTag("voiceSettings.autoCapture"))
    }

    private fun waitForWaterSetting(enabled: Boolean) {
        // The ViewModel publishes this UI state only after saving.
        waitFor(hasTestTag("settings.water.enabled") and if (enabled) isOn() else isOff())
        awaitPreference(prefs.waterTrackingEnabled) { it == enabled }
    }

    private fun <T> awaitPreference(flow: Flow<T>, predicate: (T) -> Boolean): T {
        val scope = CoroutineScope(Dispatchers.Default)
        val emissions = AtomicInteger()
        val lastSeen = AtomicReference<Any?>(null)
        val saved = scope.async {
            flow.onEach { emissions.incrementAndGet(); lastSeen.set(it) }.first { predicate(it) }
        }
        try {
            // A blocking coroutine wait starves pending Compose frame work even
            // after a menu disappears. Keep the test clock progressing while
            // observing the real DataStore transaction on a separate dispatcher.
            try {
                compose.waitUntil(timeoutMillis = 10_000) { saved.isCompleted }
            } catch (timeout: ComposeTimeoutException) {
                // Say whether the stored value never arrived or arrived but did not match.
                throw AssertionError(
                    "Saved value did not match within 10 s; observed ${emissions.get()} " +
                        "emission(s), last: ${lastSeen.get().toString().take(2_000)}",
                    timeout
                )
            }
            // Already complete: return the value or propagate the original error.
            return runBlocking { saved.await() }
        } finally {
            scope.cancel()
        }
    }

    private fun assertFoodVisible(name: String) {
        showDiaryRow(hasText(name))
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onAllNodesWithText("321 kcal").onFirst().assertExists()
    }

    private fun assertEmptyDiary() {
        val empty = string(R.string.home_no_diary_entries)
        showDiaryRow(hasText(empty))
        compose.onNodeWithText(empty).assertIsDisplayed()
    }

    private fun showDiaryRow(matcher: SemanticsMatcher) {
        waitFor(hasTestTag("home.diary"))
        compose.onNodeWithTag("home.diary").performScrollToNode(matcher)
    }

    private fun clickText(resource: Int) {
        val text = string(resource)
        waitFor(hasText(text))
        compose.onNodeWithText(text).performClick()
    }

    private fun string(resource: Int, vararg args: Any): String = context.getString(resource, *args)

    private fun waitFor(matcher: SemanticsMatcher, useUnmergedTree: Boolean = false) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(matcher, useUnmergedTree = useUnmergedTree)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun waitForAbsent(matcher: SemanticsMatcher) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }
}
