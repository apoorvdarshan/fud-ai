package com.apoorvdarshan.calorietracker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.QuickActionRequest
import com.apoorvdarshan.calorietracker.services.MealShare
import com.apoorvdarshan.calorietracker.services.QuickActionShortcutManager
import com.apoorvdarshan.calorietracker.services.ReviewPrompter
import com.apoorvdarshan.calorietracker.ui.home.ImportSharedMealSheet
import com.apoorvdarshan.calorietracker.ui.navigation.FudAINavHost
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

open class MainActivity : ComponentActivity() {
    // Shared-meal deep link (issue #107). Non-empty -> the confirm sheet is shown over the app.
    private var pendingSharedMeals by mutableStateOf<List<FoodEntry>>(emptyList())
    private var pendingQuickAction by mutableStateOf<QuickActionRequest?>(null)

    /** Decode a `fudai://add-meal` link (if that's what launched us) into pending meals. */
    private fun handleShareIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!MealShare.handles(uri)) return
        MealShare.meals(uri)?.let { pendingSharedMeals = it }
    }

    private fun handleQuickActionIntent(intent: Intent?) {
        val action = QuickActionShortcutManager.actionFrom(intent) ?: return
        pendingQuickAction = QuickActionRequest(action)
        intent?.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleQuickActionIntent(intent)
    }
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            // Adaptive Goals auto-runs the full goal calculation about once a week (Energy Burn,
            // when on, supplies the measured-burn anchor it consumes — separate toggle).
            val container = (application as FudAIApp).container
            container.refreshAdaptiveGoalsIfNeeded()
            // Pull any new external weight / body-fat readings (e.g. a Withings scale)
            // from Health Connect into the app on every foreground (issue #91).
            container.syncHealthConnectReads()
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            runCatching { (application as FudAIApp).container.cloudBackup.autoBackupIfNeeded() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the system swaps the splash theme
        // back to Theme.FudAI before the first frame, preventing a white flash
        // on cold start. The splash uses a transparent foreground mark over
        // the app's light/dark splash background.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Launch the Play in-app review card once, right after the first
        // successful food log (see ReviewPrompter). Silently no-ops on devices
        // without Play services or when Play declines to show the card.
        lifecycleScope.launch {
            ReviewPrompter.requestReview.collect { wanted ->
                if (!wanted) return@collect
                ReviewPrompter.consumed()
                delay(1_500)
                runCatching {
                    val manager = ReviewManagerFactory.create(this@MainActivity)
                    val info = manager.requestReview()
                    manager.launchReview(this@MainActivity, info)
                }
            }
        }

        // Support --reset-onboarding launch flag (parallel to iOS CLAUDE.md convention).
        if (intent?.getBooleanExtra("reset_onboarding", false) == true) {
            runBlocking { (application as FudAIApp).container.prefs.setOnboardingCompleted(false) }
            intent.removeExtra("reset_onboarding")
        }

        val container = (application as FudAIApp).container
        // A fudai://add-meal link may have cold-launched us.
        handleShareIntent(intent)
        handleQuickActionIntent(intent)

        lifecycleScope.launch {
            combine(
                container.prefs.quickAction1,
                container.prefs.quickAction2,
                container.prefs.quickAction3
            ) { first, second, third -> listOf(first, second, third) }
                .collect { QuickActionShortcutManager.update(this@MainActivity, it) }
        }

        val startOnboarding = runBlocking { !container.prefs.hasCompletedOnboarding.first() }
        val initialAppearance = runBlocking { container.prefs.appearanceMode.first() }
        val initialThemeColorKey = runBlocking { container.prefs.appThemeColor.first() }

        // Hold the splash on screen until the saved profile has loaded from
        // DataStore so Home doesn't briefly render its 2000/150/220/70 fallback
        // goal numbers before snapping to the user's real targets. Onboarding
        // doesn't show those numbers, so we let the splash dismiss immediately
        // in that case.
        var contentReady = startOnboarding
        splashScreen.setKeepOnScreenCondition { !contentReady }
        if (!startOnboarding) {
            lifecycleScope.launch {
                container.profileRepository.profile.first { it != null }
                contentReady = true
            }
        }

        setContent {
            val appearance by container.prefs.appearanceMode.collectAsState(initial = initialAppearance)
            val themeColorKey by container.prefs.appThemeColor.collectAsState(initial = initialThemeColorKey)
            val themeColor = AppThemeColor.fromKey(themeColorKey)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (appearance) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            FudAITheme(darkTheme = darkTheme, themeColor = themeColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FudAINavHost(
                        container = container,
                        startOnboarding = startOnboarding,
                        quickActionRequest = pendingQuickAction,
                        onQuickActionHandled = { requestID ->
                            if (pendingQuickAction?.id == requestID) pendingQuickAction = null
                        }
                    )

                    if (pendingSharedMeals.isNotEmpty()) {
                        ImportSharedMealSheet(
                            meals = pendingSharedMeals,
                            onAdd = { meals ->
                                lifecycleScope.launch {
                                    for (meal in meals) {
                                        if (!container.foodRepository.addEntry(meal)) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                getString(R.string.food_blocked_by_active_fast),
                                                Toast.LENGTH_LONG
                                            ).show()
                                            break
                                        }
                                    }
                                }
                                pendingSharedMeals = emptyList()
                            },
                            onDismiss = { pendingSharedMeals = emptyList() }
                        )
                    }
                }
            }
        }
    }
}
