package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateState
import com.apoorvdarshan.calorietracker.ui.coach.CoachScreen
import com.apoorvdarshan.calorietracker.ui.components.PostUpdatePromptsHost
import com.apoorvdarshan.calorietracker.ui.hosted.HostedUiHost
import com.apoorvdarshan.calorietracker.ui.home.HomeScreen
import com.apoorvdarshan.calorietracker.ui.onboarding.OnboardingScreen
import com.apoorvdarshan.calorietracker.ui.progress.BodyMeasurementsScreen
import com.apoorvdarshan.calorietracker.ui.progress.ProgressScreen
import com.apoorvdarshan.calorietracker.ui.settings.AllergenSensitivitiesScreen
import com.apoorvdarshan.calorietracker.ui.settings.CalculationMethodsScreen
import com.apoorvdarshan.calorietracker.ui.settings.OptionalNutrientGoalsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsViewModel
import com.apoorvdarshan.calorietracker.ui.workouts.WorkoutsScreen
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.QuickActionRequest
import com.apoorvdarshan.calorietracker.models.QuickAction
import com.apoorvdarshan.calorietracker.ui.settings.AddMenuSettingsScreen
import com.apoorvdarshan.calorietracker.ui.settings.ProductInfoSettingsScreen
import com.apoorvdarshan.calorietracker.ui.settings.QuickActionsScreen
import com.apoorvdarshan.calorietracker.ui.settings.TabBarSettingsScreen
import com.apoorvdarshan.calorietracker.ui.voice.VoiceAssistantHost

/**
 * Increments each time the app is opened: 1 on cold launch, then +1 on every
 * return from the background (ON_START after a real ON_STOP). Read by the Home
 * gauge + macro bars to replay their fill-from-zero reveal. It lives above the
 * NavHost, so tab switches (which recompose Home) never change it.
 */
val LocalLaunchFillEpoch = compositionLocalOf { 1 }

private const val WORKOUT_UI_PREFS = "fudai_workouts"
private const val WORKOUT_MODE_V2_DEFAULT_KEY = "mode.diary_default.v2"

@Composable
fun FudAINavHost(
    container: AppContainer,
    startOnboarding: Boolean,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    voiceLaunchRequest: Long? = null,
    onVoiceLaunchHandled: (Long) -> Unit = {}
) {
    val nav = rememberNavController()
    // Warm the app-scoped Settings state while Home is visible. By the time the user changes
    // tabs, its local profile/preferences are already ready and the page opens like every other
    // tab instead of constructing empty cards on first entry.
    val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(container)
    )
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Hide the bar while a food analysis is in flight so the AnalyzingOverlay
    // is the only thing on screen — matches iOS, where the analyzing sheet
    // covers the tab bar.
    val context = LocalContext.current
    val analyzing by container.analyzingFood.collectAsState()
    val persistedWorkoutMode by container.workoutRepository.mode.collectAsState(initial = WorkoutTabMode.Default)
    val workoutUiPrefs = remember(context) {
        context.getSharedPreferences(WORKOUT_UI_PREFS, android.content.Context.MODE_PRIVATE)
    }
    var workoutModeV2Initialized by remember(context) {
        mutableStateOf(workoutUiPrefs.getBoolean(WORKOUT_MODE_V2_DEFAULT_KEY, false))
    }
    // Match iOS's versioned AppStorage key: reset the former library-first
    // default once, then keep every user switch persistent after that.
    val workoutMode = if (workoutModeV2Initialized) persistedWorkoutMode else WorkoutTabMode.LOG
    // Nested settings/* screens keep the tab bar visible and highlight Settings,
    // so re-tapping the Settings icon can return to the hub (iOS parity).
    val selectedTabRoute = FudAIRoutes.selectedBottomTab(currentRoute)
    // The expanded voice assistant dims the whole app; hide the rail behind it.
    val voiceUi by container.voiceAssistant.ui.collectAsState()
    // A workout rest can end on any tab; mark it with a haptic while the app is open.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(container.workoutRestTimer) {
        container.workoutRestTimer.completions.collect {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    // Null until the stored choice is read, so a hidden tab never flashes in the rail.
    val hiddenTabs by container.prefs.hiddenTabs.collectAsState(initial = null)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val showTabs = selectedTabRoute != null && hiddenTabs != null && !analyzing &&
        !(voiceUi.visible && voiceUi.expanded) && !keyboardVisible
    var railCameraRequest by remember { mutableStateOf<QuickActionRequest?>(null) }
    val homeActionRequest = quickActionRequest ?: railCameraRequest
    // Bumped when the Settings tab is re-tapped so SettingsScreen can pop back to the hub
    // (matches iOS TabView + NavigationStack reselect → root behavior).
    var settingsPopToRootTick by remember { mutableIntStateOf(0) }
    val currentVersion = remember(context) { AndroidUpdateChecker.currentVersion(context) }
    var updateAvailable by remember { mutableStateOf(false) }

    // Settings is intentionally warmed before onboarding finishes. Reload the values that
    // onboarding can change outside Settings so the first visit never shows the pre-onboarding
    // provider/model/API-key snapshot (issue #170). Re-running this on later visits also keeps
    // the app-scoped ViewModel honest if another flow updates the stored AI configuration.
    LaunchedEffect(currentRoute) {
        if (currentRoute == FudAIRoutes.SETTINGS) {
            settingsViewModel.refreshAiConfiguration()
        }
    }

    LaunchedEffect(homeActionRequest?.id, currentRoute) {
        if (homeActionRequest != null &&
            currentRoute != FudAIRoutes.HOME &&
            currentRoute != FudAIRoutes.ONBOARDING
        ) {
            nav.navigate(FudAIRoutes.HOME) {
                popUpTo(FudAIRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(container.workoutRepository, workoutModeV2Initialized) {
        if (!workoutModeV2Initialized) {
            container.workoutRepository.setMode(WorkoutTabMode.LOG)
            workoutUiPrefs.edit().putBoolean(WORKOUT_MODE_V2_DEFAULT_KEY, true).apply()
            workoutModeV2Initialized = true
        }
    }

    LaunchedEffect(currentVersion) {
        if (!container.backgroundServicesEnabled) return@LaunchedEffect
        val state = AndroidUpdateChecker.check(context, currentVersion)
        updateAvailable = state is AndroidUpdateState.Available
        // A newer version is out — fire a one-shot notification (de-duped per version, gated by the
        // "App Updates" toggle) so the user finds out even without opening the About section.
        if (state is AndroidUpdateState.Available &&
            container.prefs.appUpdateNotificationsEnabled.first() &&
            container.notifications.canPostNotifications() &&
            container.prefs.lastNotifiedUpdateVersion.first() != state.latest
        ) {
            container.notifications.showUpdateAvailable()
            container.prefs.setLastNotifiedUpdateVersion(state.latest)
        }
    }

    // App-open epoch for the Home fill-from-zero reveal. Bumped only on ON_START
    // that follows an ON_STOP (a genuine background -> foreground return), so
    // transient pauses (notification shade, permission dialog) don't retrigger it.
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchFillEpoch by remember { mutableIntStateOf(1) }
    var hasStopped by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> hasStopped = true
                Lifecycle.Event.ON_START -> if (hasStopped) { launchFillEpoch++; hasStopped = false }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Shows a tab like a tap on the bar. It reads the live route, so requests from outside
    // composition (the voice assistant) act on what is on screen now.
    fun selectTab(target: String) {
        if (FudAIRoutes.isHiddenTab(target, container.navigator.hiddenTabs.value)) return
        val route = nav.current()
        // Re-tapping Settings while already in Settings (hub category or a
        // nested settings/* destination) returns to the Settings hub — same
        // as iOS re-tapping the Settings tab to pop the navigation stack.
        if (target == FudAIRoutes.SETTINGS) {
            val onSettingsRoot = route == FudAIRoutes.SETTINGS
            val onSettingsChild = route?.startsWith("settings/") == true
            if (onSettingsRoot || onSettingsChild) {
                settingsPopToRootTick++
                if (onSettingsChild) {
                    nav.popBackStack(FudAIRoutes.SETTINGS, inclusive = false)
                }
                return
            }
        }
        if (target == FudAIRoutes.selectedBottomTab(route)) return
        // Tapping HOME (the start destination) needs popBackStack
        // — `navigate(HOME) { popUpTo(HOME); launchSingleTop = true }`
        // is a no-op because NavController sees HOME at the top of
        // the stack and skips re-emitting currentBackStackEntry, so
        // the bar stays selected on the previous tab.
        if (target == FudAIRoutes.HOME) {
            nav.popBackStack(FudAIRoutes.HOME, inclusive = false)
        } else {
            nav.navigate(target) {
                popUpTo(FudAIRoutes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // What is on screen, for the voice assistant's get_app_state.
    LaunchedEffect(currentRoute) {
        container.navigator.currentRoute.value = currentRoute
    }

    // A tab hidden in Settings (or by a restored backup) can't stay on screen.
    LaunchedEffect(hiddenTabs, currentRoute) {
        val hidden = hiddenTabs ?: return@LaunchedEffect
        container.navigator.hiddenTabs.value = hidden
        if (FudAIRoutes.isHiddenTab(currentRoute, hidden)) {
            nav.popBackStack(FudAIRoutes.HOME, inclusive = false)
        }
    }

    // Tabs and Settings sections the voice assistant opens, performed like the user's taps.
    LaunchedEffect(nav) {
        container.navigator.requests.collect { request ->
            val route = nav.current()
            if (route == null || route == FudAIRoutes.ONBOARDING) return@collect
            when (request) {
                is NavRequest.Tab -> selectTab(request.route)
                is NavRequest.Settings -> {
                    when {
                        route.startsWith("settings/") -> nav.popBackStack(FudAIRoutes.SETTINGS, inclusive = false)
                        route != FudAIRoutes.SETTINGS -> nav.navigate(FudAIRoutes.SETTINGS) {
                            popUpTo(FudAIRoutes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                    // A section opens from the Settings screen itself; none means the hub.
                    if (request.section == null) settingsPopToRootTick++
                }
            }
        }
    }

    // The Quick Settings tile, launcher shortcut and widget start a conversation.
    LaunchedEffect(voiceLaunchRequest, currentRoute) {
        val request = voiceLaunchRequest ?: return@LaunchedEffect
        if (currentRoute == null || currentRoute == FudAIRoutes.ONBOARDING) return@LaunchedEffect
        container.voiceAssistant.requestStart()
        onVoiceLaunchHandled(request)
    }

    val tabContent: @Composable () -> Unit = {
        NavHost(
                modifier = Modifier.fillMaxSize(),
                navController = nav,
                startDestination = if (startOnboarding) FudAIRoutes.ONBOARDING else FudAIRoutes.HOME
            ) {
                composable(FudAIRoutes.ONBOARDING) {
                    OnboardingScreen(container = container, onComplete = {
                        settingsViewModel.refreshAiConfiguration()
                        nav.navigate(FudAIRoutes.HOME) {
                            popUpTo(FudAIRoutes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
                composable(FudAIRoutes.HOME) {
                    TabInset {
                        HomeScreen(
                            container = container,
                            quickActionRequest = homeActionRequest,
                            onQuickActionHandled = { id ->
                                if (railCameraRequest?.id == id) railCameraRequest = null
                                else onQuickActionHandled(id)
                            }
                        )
                    }
                }
                composable(FudAIRoutes.PROGRESS) { TabInset { ProgressScreen(container = container) } }
                composable(FudAIRoutes.COACH) { TabInset { CoachScreen(container = container) } }
                composable(FudAIRoutes.SETTINGS) {
                    TabInset {
                        SettingsScreen(
                            container = container,
                            nav = nav,
                            vm = settingsViewModel,
                            popToRootTick = settingsPopToRootTick
                        )
                    }
                }
                composable(FudAIRoutes.OPTIONAL_NUTRIENT_GOALS) {
                    OptionalNutrientGoalsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.CALCULATION_METHODS) {
                    CalculationMethodsScreen(onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.QUICK_ACTIONS) {
                    QuickActionsScreen(vm = settingsViewModel, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.ADD_MENU) {
                    AddMenuSettingsScreen(vm = settingsViewModel, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.PRODUCT_INFO) {
                    ProductInfoSettingsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.TAB_BAR) {
                    TabBarSettingsScreen(
                        vm = settingsViewModel,
                        workoutMode = workoutMode,
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(FudAIRoutes.BODY_MEASUREMENTS) {
                    BodyMeasurementsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.ALLERGEN_SENSITIVITIES) {
                    val settingsUi by settingsViewModel.ui.collectAsState()
                    AllergenSensitivitiesScreen(
                        current = settingsUi.profile?.allergenSensitivities.orEmpty(),
                        onSave = { values -> settingsViewModel.updateProfile { it.copy(allergenSensitivities = values) } },
                        onBack = { nav.popBackStack() },
                        foodAnalysis = container.foodAnalysis
                    )
                }
                composable(FudAIRoutes.WORKOUTS) { TabInset { WorkoutsScreen(container = container) } }
            }
    }
    val layoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLaunchFillEpoch provides launchFillEpoch) {
        Box(Modifier.fillMaxSize()) {
            // Keep the rail physically on the right, without changing the
            // direction of translated content within either pane.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                        Box(Modifier.weight(1f), propagateMinConstraints = true) { tabContent() }
                        if (showTabs) {
                            PinkNeoRail(
                                currentRoute = selectedTabRoute,
                                showAboutBadge = updateAvailable,
                                workoutMode = workoutMode,
                                hiddenRoutes = hiddenTabs.orEmpty(),
                                onTap = ::selectTab,
                                onCamera = { railCameraRequest = QuickActionRequest(QuickAction.CAMERA) }
                            )
                        }
                    }
                }
            }
            // One-time post-update prompts for existing users; never during (or right after)
            // a fresh onboarding — those users are marked as "seen" on completion.
            PostUpdatePromptsHost(
                container = container,
                enabled = !startOnboarding && container.backgroundServicesEnabled
            )
            // The live voice assistant over every tab (never during onboarding).
            if (currentRoute != null && currentRoute != FudAIRoutes.ONBOARDING) {
                VoiceAssistantHost(container = container)
            }
            // Paywall, credits, and out-of-quota UI for Hosted AI, reachable from any screen
            // (including onboarding).
            HostedUiHost(container = container)
        }
    }
}

/**
 * Reserves the status bar above each primary page; nested Scaffolds see the
 * inset as consumed, while the rail handles its own system bars.
 */
@Composable
private fun TabInset(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

internal fun NavHostController.current(): String? = currentBackStackEntry?.destination?.route
