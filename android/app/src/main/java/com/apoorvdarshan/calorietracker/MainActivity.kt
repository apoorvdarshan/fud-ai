package com.apoorvdarshan.calorietracker

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.apoorvdarshan.calorietracker.data.StartupPreferences
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.QuickActionRequest
import com.apoorvdarshan.calorietracker.services.MealShare
import com.apoorvdarshan.calorietracker.services.QuickActionShortcutManager
import com.apoorvdarshan.calorietracker.services.ReviewPrompter
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import com.apoorvdarshan.calorietracker.services.voice.VoiceEntryPoints
import com.apoorvdarshan.calorietracker.ui.components.LocalEnergyUnit
import com.apoorvdarshan.calorietracker.ui.components.MascotCafeIntro
import com.apoorvdarshan.calorietracker.ui.home.ImportSharedMealSheet
import com.apoorvdarshan.calorietracker.ui.navigation.FudAINavHost
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AppCompatActivity so AppCompatDelegate.setApplicationLocales applies on API 26–32 (#372).
open class MainActivity : AppCompatActivity() {
    // Shared-meal deep link (issue #107). Non-empty -> the confirm sheet is shown over the app.
    private var pendingSharedMeals by mutableStateOf<List<FoodEntry>>(emptyList())
    private var pendingQuickAction by mutableStateOf<QuickActionRequest?>(null)
    // Tile, launcher shortcut or widget: open straight into a voice conversation.
    private var pendingVoiceLaunch by mutableStateOf<Long?>(null)

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

    private fun handleVoiceIntent(intent: Intent?) {
        if (!VoiceEntryPoints.isVoiceLaunch(intent)) return
        pendingVoiceLaunch = System.nanoTime()
        VoiceEntryPoints.consume(intent)
    }

    private fun handlePlayStoreIntent(intent: Intent?) {
        if (intent?.action != AndroidUpdateChecker.ACTION_OPEN_PLAY_STORE) return
        AndroidUpdateChecker.openPlayStore(this)
        intent.action = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleQuickActionIntent(intent)
        handlePlayStoreIntent(intent)
        handleVoiceIntent(intent)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingVoiceLaunch?.let { outState.putLong("pendingVoiceLaunch", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        // A rest that ends while the app is visible is announced in-app, not by notification.
        (application as FudAIApp).container.workoutRestTimer.setAppInForeground(true)
        if (!(application as FudAIApp).backgroundServicesEnabled) return
        lifecycleScope.launch {
            // Adaptive Goals auto-runs the full goal calculation about once a week (Energy Burn,
            // when on, supplies the measured-burn anchor it consumes — separate toggle).
            val container = (application as FudAIApp).container
            // Keep the Hosted AI plan current (renewals, cancellations, pending purchases)
            // before Adaptive Goals, which may run through Hosted AI. The quota refresh
            // runs alongside so it does not hold up the work below.
            if (container.billing.refreshCustomerInfo() && container.billing.entitlement.value.isEntitled) {
                launch { container.hostedQuota.refresh() }
            }
            container.refreshAdaptiveGoalsIfNeeded()
            // Pull any new external weight / body-fat readings (e.g. a Withings scale)
            // from Health Connect into the app on every foreground (issue #91).
            container.syncHealthConnectReads()
        }
    }

    override fun onStop() {
        super.onStop()
        (application as FudAIApp).container.workoutRestTimer.setAppInForeground(false)
        if (!(application as FudAIApp).backgroundServicesEnabled) return
        lifecycleScope.launch {
            runCatching { (application as FudAIApp).container.cloudBackup.autoBackupIfNeeded() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val activityStart = SystemClock.elapsedRealtime()
        // Must run before super.onCreate so the system swaps the splash theme
        // back to Theme.FudAI before the first frame, preventing a white flash
        // on cold start. The splash uses a transparent foreground mark over
        // the app's light/dark splash background.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as FudAIApp
        // Launch the Play in-app review card once, right after the first
        // successful food log (see ReviewPrompter). Silently no-ops on devices
        // without Play services or when Play declines to show the card.
        lifecycleScope.launch {
            if (!(application as FudAIApp).backgroundServicesEnabled) return@launch
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

        val container = (application as FudAIApp).container
        // Consume the explicit developer flag before starting the async read.
        val resetOnboarding = intent?.getBooleanExtra("reset_onboarding", false) == true
        if (resetOnboarding) intent?.removeExtra("reset_onboarding")
        // A fudai://add-meal link may have cold-launched us.
        handleShareIntent(intent)
        handleQuickActionIntent(intent)
        handlePlayStoreIntent(intent)
        // Preserve an external request while onboarding or activity recreation delays it.
        if (savedInstanceState?.containsKey("pendingVoiceLaunch") == true) {
            pendingVoiceLaunch = savedInstanceState.getLong("pendingVoiceLaunch")
        } else if (savedInstanceState == null) handleVoiceIntent(intent)

        lifecycleScope.launch {
            combine(
                container.prefs.quickAction1,
                container.prefs.quickAction2,
                container.prefs.quickAction3
            ) { first, second, third -> listOf(first, second, third) }
                .collect { QuickActionShortcutManager.update(this@MainActivity, it) }
        }

        // Keep the splash while the first-frame inputs load without blocking
        // the main thread. A missing profile can no longer hold it forever.
        var contentReady = false
        splashScreen.setKeepOnScreenCondition { !contentReady }
        lifecycleScope.launch {
            val prefsStart = SystemClock.elapsedRealtime()
            val startup = withContext(Dispatchers.Default) {
                if (resetOnboarding) container.prefs.setOnboardingCompleted(false)
                container.prefs.startupPreferences()
            }
            val prefsEnd = SystemClock.elapsedRealtime()
            showContent(container, startup) {
                contentReady = true
                if (BuildConfig.DEBUG) {
                    Log.d("FudStartup", "Activity: preferences=${prefsEnd - prefsStart}ms; " +
                        "composition=${SystemClock.elapsedRealtime() - prefsEnd}ms; " +
                        "total=${SystemClock.elapsedRealtime() - activityStart}ms")
                }
            }
        }
    }

    private fun showContent(container: AppContainer, startup: StartupPreferences, onFirstContentReady: () -> Unit) {
        setContent {
            val platformHaptics = LocalHapticFeedback.current
            val hapticsEnabled by container.hapticsEnabled.collectAsState()
            val enabled = rememberUpdatedState(hapticsEnabled)
            val appHaptics = remember(platformHaptics) {
                object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        if (enabled.value) platformHaptics.performHapticFeedback(hapticFeedbackType)
                    }
                }
            }
            val appearance by container.prefs.appearanceMode.collectAsState(initial = startup.appearanceMode)
            val themeColorKey by container.prefs.appThemeColor.collectAsState(initial = startup.themeColorKey)
            val themeColor = AppThemeColor.fromKey(themeColorKey)
            val energyUnit by container.prefs.energyUnit.collectAsState(initial = startup.energyUnit)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (appearance) {
                "light" -> false
                "dark", "darkGray" -> true
                else -> systemDark
            }
            SideEffect {
                // Edge-to-edge defaults follow the device, but the app can
                // select an independent background behind the system bars.
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            FudAITheme(darkTheme = darkTheme, themeColor = themeColor, grayDarkTheme = appearance == "darkGray") {
                val welcome = remember { getSharedPreferences("welcome", MODE_PRIVATE) }
                var seenIntro by remember { mutableStateOf(welcome.getBoolean("seenMascotCafeWelcome", false)) }
                if (!seenIntro && intent.action == Intent.ACTION_MAIN && pendingVoiceLaunch == null &&
                    pendingQuickAction == null && pendingSharedMeals.isEmpty()
                ) {
                    MascotCafeIntro(container.billing) {
                        welcome.edit().putBoolean("seenMascotCafeWelcome", true).apply()
                        seenIntro = true
                    }
                }
                CompositionLocalProvider(
                    LocalEnergyUnit provides energyUnit,
                    LocalHapticFeedback provides appHaptics
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        FudAINavHost(
                            container = container,
                            startup = startup,
                            onFirstContentReady = onFirstContentReady,
                            quickActionRequest = pendingQuickAction,
                            onQuickActionHandled = { requestID ->
                                if (pendingQuickAction?.id == requestID) pendingQuickAction = null
                            },
                            voiceLaunchRequest = pendingVoiceLaunch,
                            onVoiceLaunchHandled = { requestID ->
                                if (pendingVoiceLaunch == requestID) pendingVoiceLaunch = null
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
}
