package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.apoorvdarshan.calorietracker.ui.progress.WeeklyChallengeScreen
import com.apoorvdarshan.calorietracker.ui.settings.AllergenSensitivitiesScreen
import com.apoorvdarshan.calorietracker.ui.settings.CalculationMethodsScreen
import com.apoorvdarshan.calorietracker.ui.settings.OptionalNutrientGoalsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsViewModel
import com.apoorvdarshan.calorietracker.ui.workouts.WorkoutsScreen
import com.apoorvdarshan.calorietracker.models.WorkoutTabMode
import com.apoorvdarshan.calorietracker.models.UserProfile
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


@Composable
fun FudAINavHost(
    container: AppContainer,
    startOnboarding: Boolean,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    voiceLaunchRequest: Long? = null,
    onVoiceLaunchHandled: (Long) -> Unit = {},
    initialHomeProfile: UserProfile? = null
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
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val analyzing by container.analyzingFood.collectAsState()
    val voiceUi = container.voiceAssistant.ui.collectAsState()
    val voiceExpanded by remember(voiceUi) {
        derivedStateOf(structuralEqualityPolicy()) { voiceUi.value.let { it.visible && it.expanded } }
    }
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
    // Null until the stored choice is read, so a hidden tab never flashes in the rail.
    val hiddenTabs by container.prefs.hiddenTabs.collectAsState(initial = null)
    // Keep the content width stable. The rail handles IME insets itself;
    // full-screen presentations can still cover the underlying navigation.
    val showTabs = selectedTabRoute != null && hiddenTabs != null
    ReportDrawnWhen {
        currentRoute == FudAIRoutes.ONBOARDING || (selectedTabRoute != null && hiddenTabs != null)
    }
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
                 startDestination = if (startOnboarding) FudAIRoutes.ONBOARDING else FudAIRoutes.HOME,
                 // The rail already indicates a new tab. Do not cross-fade two
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
                            initialProfile = initialHomeProfile,
                            quickActionRequest = homeActionRequest,
                            onQuickActionHandled = { id ->
                                if (railCameraRequest?.id == id) railCameraRequest = null
                                else onQuickActionHandled(id)
                            }
                        )
                    }
                }
                composable(FudAIRoutes.PROGRESS) { TabInset { ProgressScreen(container = container) } }
                composable(FudAIRoutes.WEEKLY_CHALLENGE) { TabInset { WeeklyChallengeScreen(container = container) } }
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
                composable(FudAIRoutes.WORKOUTS) { TabInset { WorkoutsScreen(container, WorkoutTabMode.LOG) } }
                composable(FudAIRoutes.EXERCISE_LIBRARY) { TabInset { WorkoutsScreen(container, WorkoutTabMode.LIBRARY) } }
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
                                hiddenRoutes = hiddenTabs.orEmpty(),
                                navigationEnabled = !analyzing && !voiceExpanded,
                                onTap = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    selectTab(it)
                                },
                                onCamera = {
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    railCameraRequest = QuickActionRequest(QuickAction.CAMERA)
                                }
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
