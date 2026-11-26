package com.apoorvdarshan.calorietracker.ui.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.data.StartupPreferences
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateState
import com.apoorvdarshan.calorietracker.ui.about.AboutSettingsCategory
import com.apoorvdarshan.calorietracker.ui.coach.CoachScreen
import com.apoorvdarshan.calorietracker.ui.components.HostedUpdateInviteHost
import com.apoorvdarshan.calorietracker.ui.hosted.HostedUiHost
import com.apoorvdarshan.calorietracker.ui.home.HomeScreen
import com.apoorvdarshan.calorietracker.ui.onboarding.OnboardingScreen
import com.apoorvdarshan.calorietracker.ui.progress.BodyMeasurementsScreen
import com.apoorvdarshan.calorietracker.ui.progress.ProgressScreen
import com.apoorvdarshan.calorietracker.ui.progress.WeeklyChallengeScreen
import com.apoorvdarshan.calorietracker.ui.settings.AllergenSensitivitiesScreen
import com.apoorvdarshan.calorietracker.ui.settings.AboutRailScreen
import com.apoorvdarshan.calorietracker.ui.settings.CalculationMethodsScreen
import com.apoorvdarshan.calorietracker.ui.settings.OptionalNutrientGoalsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsViewModel
import com.apoorvdarshan.calorietracker.ui.workouts.WorkoutsScreen
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.QuickActionRequest
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


@Composable
internal fun FudAINavHost(
    container: AppContainer,
    startup: StartupPreferences,
    onFirstContentReady: () -> Unit,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    voiceLaunchRequest: Long? = null,
    onVoiceLaunchHandled: (Long) -> Unit = {}
) {
    val nav = rememberNavController()
    // Share one activity-scoped Settings model across its pages, but don't start
    // its preference, key and workout observers just to draw Home.
    val settingsOwner = checkNotNull(LocalViewModelStoreOwner.current)
    val settingsFactory = remember(container) { SettingsViewModel.Factory(container) }
    val settingsModel: @Composable () -> SettingsViewModel = {
        viewModel(viewModelStoreOwner = settingsOwner, factory = settingsFactory)
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val analyzing by container.analyzingFood.collectAsState()
    val voiceUi = container.voiceAssistant.ui.collectAsState()
    val voiceExpanded by remember(voiceUi) {
        derivedStateOf(structuralEqualityPolicy()) { voiceUi.value.let { it.visible && it.expanded } }
    }
    val workoutMode by container.workoutRepository.mode.collectAsState(initial = WorkoutTabMode.Default)
    // Nested settings/* screens keep the tab bar visible and highlight Settings,
    // so re-tapping the Settings icon can return to the hub (iOS parity).
    val selectedTabRoute = FudAIRoutes.selectedBottomTab(currentRoute)
    // A workout rest can end on any tab; mark it with a haptic while the app is open.
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(container.workoutRestTimer) {
        container.workoutRestTimer.completions.collect {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }
    val hiddenTabs by container.prefs.hiddenTabs.collectAsState(initial = startup.hiddenTabs)
    val showTabs = selectedTabRoute != null
    var firstContentReady by remember { mutableStateOf(false) }
    ReportDrawnWhen { firstContentReady }
    // Bumped when the Settings tab is re-tapped so SettingsScreen can pop back to the hub
    // (matches iOS TabView + NavigationStack reselect → root behavior).
    var settingsPopToRootTick by remember { mutableIntStateOf(0) }
    val currentVersion = remember(context) { AndroidUpdateChecker.currentVersion(context) }
    var appUpdateState by remember { mutableStateOf<AndroidUpdateState>(AndroidUpdateState.Idle) }
    var checkingAppUpdate by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()

    LaunchedEffect(quickActionRequest?.id, currentRoute) {
        if (quickActionRequest != null &&
            currentRoute != FudAIRoutes.HOME &&
            currentRoute != FudAIRoutes.ONBOARDING
        ) {
            nav.navigate(FudAIRoutes.HOME) {
                popUpTo(FudAIRoutes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    fun refreshAppUpdateState() {
        if (!container.backgroundServicesEnabled || checkingAppUpdate) return
        checkingAppUpdate = true
        updateScope.launch {
            try {
                if (!appUpdateState.isUpdateAvailable) appUpdateState = AndroidUpdateState.Checking
                val result = AndroidUpdateChecker.check(context, currentVersion)
                appUpdateState = appUpdateState.resolving(result)
                // Notify once per confirmed version, independently of the in-app badges.
                if (result is AndroidUpdateState.Available &&
                    container.prefs.appUpdateNotificationsEnabled.first() &&
                    container.notifications.canPostNotifications() &&
                    container.prefs.lastNotifiedUpdateVersion.first() != result.latest
                ) {
                    container.notifications.showUpdateAvailable()
                    container.prefs.setLastNotifiedUpdateVersion(result.latest)
                }
            } finally {
                checkingAppUpdate = false
            }
        }
    }

    // App-open epoch for the Home fill-from-zero reveal. Bumped only on ON_START
    // that follows an ON_STOP (a genuine background -> foreground return), so
    // transient pauses (notification shade, permission dialog) don't retrigger it.
    val lifecycleOwner = LocalLifecycleOwner.current
    var launchFillEpoch by remember { mutableIntStateOf(1) }
    var hasStopped by remember { mutableStateOf(false) }
    LaunchedEffect(currentVersion, launchFillEpoch) {
        refreshAppUpdateState()
    }
    DisposableEffect(lifecycleOwner, container.navigator) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> hasStopped = true
                Lifecycle.Event.ON_START -> {
                    container.navigator.refreshDiaryDates()
                    if (hasStopped) { launchFillEpoch++; hasStopped = false }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, container.navigator) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.navigator.refreshDiaryDates()
            }
        }
        val filter = IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
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
            if (FudAIRoutes.selectedBottomTab(route) == FudAIRoutes.SETTINGS &&
                nav.popBackStack(FudAIRoutes.SETTINGS, inclusive = false)
            ) return
        }
        if (target == route || (target == FudAIRoutes.ABOUT && route == FudAIRoutes.ABOUT_SECTION)) {
            if (target == FudAIRoutes.ABOUT && route == FudAIRoutes.ABOUT_SECTION) {
                nav.popBackStack(FudAIRoutes.ABOUT, inclusive = false)
            }
            return
        }
        // Tapping HOME (the start destination) needs popBackStack
        // — `navigate(HOME) { popUpTo(HOME); launchSingleTop = true }`
        // is a no-op because NavController sees HOME at the top of
        // the stack and skips re-emitting currentBackStackEntry, so
        // the bar stays selected on the previous tab.
        if (target == FudAIRoutes.HOME) {
            nav.popBackStack(FudAIRoutes.HOME, inclusive = false, saveState = true)
        } else {
            nav.navigate(target) {
                popUpTo(FudAIRoutes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // What is on screen, for the voice assistant's get_app_state.
    val aboutSection = backStack?.arguments?.getString("section")
    LaunchedEffect(currentRoute, aboutSection) {
        container.navigator.currentRoute.value = currentRoute
        container.navigator.aboutSection.value = if (currentRoute == FudAIRoutes.ABOUT_SECTION) {
            AboutSettingsCategory.entries.firstOrNull { it.name == aboutSection }
        } else null
    }

    // A tab hidden in Settings (or by a restored backup) can't stay on screen.
    LaunchedEffect(hiddenTabs, currentRoute) {
        container.navigator.hiddenTabs.value = hiddenTabs
        if (FudAIRoutes.isHiddenTab(currentRoute, hiddenTabs)) {
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
                is NavRequest.About -> {
                    selectTab(FudAIRoutes.ABOUT)
                    nav.navigate(FudAIRoutes.aboutSection(request.section.name)) {
                        popUpTo(FudAIRoutes.ABOUT)
                        launchSingleTop = true
                    }
                }
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
                 startDestination = if (startup.startOnboarding) FudAIRoutes.ONBOARDING else FudAIRoutes.HOME,
                 // The bottom bar already indicates a new tab. Do not cross-fade two
                 // full screens or keep both composed through a tab switch.
                 enterTransition = {
                     if (FudAIRoutes.isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                         EnterTransition.None
                     } else fadeIn()
                 },
                 exitTransition = {
                     if (FudAIRoutes.isTabSwitch(initialState.destination.route, targetState.destination.route)) {
                         ExitTransition.None
                     } else fadeOut()
                 }
            ) {
                composable(FudAIRoutes.ONBOARDING) {
                    OnboardingScreen(container = container, onComplete = {
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
                            initialProfile = startup.profile,
                            initialVoiceMascots = startup.voiceMascots,
                            initialHideVoiceShortcut = startup.hideVoiceShortcut,
                            quickActionRequest = quickActionRequest,
                            onQuickActionHandled = onQuickActionHandled
                        )
                    }
                }
                composable(FudAIRoutes.PROGRESS) {
                    TabInset { ProgressScreen(container = container, onChallenges = { nav.navigate(FudAIRoutes.WEEKLY_CHALLENGE) }) }
                }
                composable(FudAIRoutes.WEEKLY_CHALLENGE) {
                    TabInset {
                        WeeklyChallengeScreen(container = container, onBack = {
                            if (!nav.popBackStack(FudAIRoutes.PROGRESS, inclusive = false)) selectTab(FudAIRoutes.PROGRESS)
                        })
                    }
                }
                composable(FudAIRoutes.COACH) { TabInset { CoachScreen(container = container) } }
                composable(FudAIRoutes.SETTINGS) {
                    val settingsViewModel = settingsModel()
                    // Other flows can change provider settings between visits.
                    LaunchedEffect(settingsViewModel) { settingsViewModel.refreshAiConfiguration() }
                    TabInset {
                        SettingsScreen(
                            container = container,
                            nav = nav,
                            vm = settingsViewModel,
                            updateAvailable = appUpdateState.isUpdateAvailable,
                            popToRootTick = settingsPopToRootTick
                        )
                    }
                }
                composable(FudAIRoutes.ABOUT) {
                    TabInset {
                        AboutRailScreen(
                            container = container,
                            updateState = appUpdateState,
                            onSelect = { nav.navigate(FudAIRoutes.aboutSection(it.name)) },
                            onBack = { selectTab(FudAIRoutes.SETTINGS) }
                        )
                    }
                }
                composable(FudAIRoutes.ABOUT_SECTION) { entry ->
                    val category = AboutSettingsCategory.entries.firstOrNull {
                        it.name == entry.arguments?.getString("section") && it != AboutSettingsCategory.SUPPORT
                    } ?: AboutSettingsCategory.APP_UPDATES
                    TabInset {
                        AboutRailScreen(
                            container = container,
                            category = category,
                            updateState = appUpdateState,
                            onRefreshUpdates = ::refreshAppUpdateState,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
                composable(FudAIRoutes.SUPPORT) {
                    TabInset {
                        AboutRailScreen(container = container, category = AboutSettingsCategory.SUPPORT,
                            onBack = { selectTab(FudAIRoutes.SETTINGS) })
                    }
                }
                composable(FudAIRoutes.OPTIONAL_NUTRIENT_GOALS) {
                    OptionalNutrientGoalsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.CALCULATION_METHODS) {
                    CalculationMethodsScreen(onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.QUICK_ACTIONS) {
                    QuickActionsScreen(vm = settingsModel(), onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.ADD_MENU) {
                    AddMenuSettingsScreen(vm = settingsModel(), onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.PRODUCT_INFO) {
                    ProductInfoSettingsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.TAB_BAR) {
                    TabBarSettingsScreen(
                        vm = settingsModel(),
                        onBack = { nav.popBackStack() }
                    )
                }
                composable(FudAIRoutes.BODY_MEASUREMENTS) {
                    BodyMeasurementsScreen(container = container, onBack = { nav.popBackStack() })
                }
                composable(FudAIRoutes.ALLERGEN_SENSITIVITIES) {
                    val settingsViewModel = settingsModel()
                    val settingsUi by settingsViewModel.ui.collectAsState()
                    AllergenSensitivitiesScreen(
                        current = settingsUi.profile?.allergenSensitivities.orEmpty(),
                        onSave = { values -> settingsViewModel.updateProfile { it.copy(allergenSensitivities = values) } },
                        onBack = { nav.popBackStack() },
                        foodAnalysis = container.foodAnalysis
                    )
                }
                composable(FudAIRoutes.WORKOUTS) {
                    TabInset {
                        WorkoutsScreen(container, WorkoutTabMode.LOG, onSwitchMode = {
                            updateScope.launch { container.workoutRepository.setMode(WorkoutTabMode.LIBRARY) }
                            selectTab(FudAIRoutes.EXERCISE_LIBRARY)
                        })
                    }
                }
                composable(FudAIRoutes.EXERCISE_LIBRARY) {
                    TabInset {
                        WorkoutsScreen(container, WorkoutTabMode.LIBRARY, onSwitchMode = {
                            updateScope.launch { container.workoutRepository.setMode(WorkoutTabMode.LOG) }
                            selectTab(FudAIRoutes.WORKOUTS)
                        })
                    }
                }
            }
    }
    CompositionLocalProvider(LocalLaunchFillEpoch provides launchFillEpoch) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f).then(if (showTabs) Modifier.consumeWindowInsets(
                        WindowInsets.navigationBars
                    ) else Modifier),
                    propagateMinConstraints = true
                ) { tabContent() }
                if (showTabs) {
                    FudAIBottomNavBar(
                        currentRoute = selectedTabRoute,
                        showAboutBadge = appUpdateState.isUpdateAvailable,
                        hiddenRoutes = hiddenTabs,
                        navigationEnabled = !analyzing && !voiceExpanded,
                        workoutMode = when (currentRoute) {
                            FudAIRoutes.EXERCISE_LIBRARY -> WorkoutTabMode.LIBRARY
                            FudAIRoutes.WORKOUTS -> WorkoutTabMode.LOG
                            else -> workoutMode
                        },
                        onTap = {
                            if (it != selectedTabRoute) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            focus.clearFocus()
                            keyboard?.hide()
                            selectTab(if (it == FudAIRoutes.WORKOUTS && workoutMode == WorkoutTabMode.LIBRARY) {
                                FudAIRoutes.EXERCISE_LIBRARY
                            } else it)
                        }
                    )
                }
            }
            // Never during or right after onboarding; fresh installs are marked seen.
            HostedUpdateInviteHost(
                container = container,
                enabled = !startup.startOnboarding && container.backgroundServicesEnabled,
                canPresent = { currentRoute == FudAIRoutes.HOME && quickActionRequest == null && !analyzing }
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
    // Release the native splash after applying the shell composition that has
    // both the destination and its tab bar. Pre-draw is still blocked here; waiting
    // for a global positioning or draw callback can stall the handoff.
    if (!firstContentReady && (currentRoute == FudAIRoutes.ONBOARDING ||
            (showTabs && !FudAIRoutes.isHiddenTab(selectedTabRoute, hiddenTabs)))) {
        SideEffect {
            firstContentReady = true
            onFirstContentReady()
        }
    }
}

/**
 * Reserves the status bar above each primary page; nested Scaffolds see the
 * inset as consumed, while the bottom bar handles its own system bars.
 */
@Composable
private fun TabInset(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) { content() }
    }
}

internal fun NavHostController.current(): String? = currentBackStackEntry?.destination?.route
