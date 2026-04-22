package com.apoorvdarshan.calorietracker.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.ui.about.AboutScreen
import com.apoorvdarshan.calorietracker.ui.coach.CoachScreen
import com.apoorvdarshan.calorietracker.ui.home.HomeScreen
import com.apoorvdarshan.calorietracker.ui.onboarding.OnboardingScreen
import com.apoorvdarshan.calorietracker.ui.progress.ProgressScreen
import com.apoorvdarshan.calorietracker.ui.settings.SettingsScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun FudAINavHost(
    container: AppContainer,
    startOnboarding: Boolean
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = currentRoute in FudAIRoutes.bottomTabs
    val hazeState = remember { HazeState() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Content layer — marked as the blur source so the floating bar sees it.
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            NavHost(
                navController = nav,
                startDestination = if (startOnboarding) FudAIRoutes.ONBOARDING else FudAIRoutes.HOME
            ) {
                composable(FudAIRoutes.ONBOARDING) {
                    OnboardingScreen(container = container, onComplete = {
                        nav.navigate(FudAIRoutes.HOME) {
                            popUpTo(FudAIRoutes.ONBOARDING) { inclusive = true }
                            launchSingleTop = true
                        }
                    })
                }
                composable(FudAIRoutes.HOME) { HomeScreen(container = container) }
                composable(FudAIRoutes.PROGRESS) { ProgressScreen(container = container) }
                composable(FudAIRoutes.COACH) { CoachScreen(container = container) }
                composable(FudAIRoutes.SETTINGS) { SettingsScreen(container = container, nav = nav) }
                composable(FudAIRoutes.ABOUT) { AboutScreen(container = container) }
            }
        }

        if (showTabs) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                FudAIBottomNavBar(
                    currentRoute = currentRoute,
                    onTap = { target ->
                        if (target != currentRoute) {
                            nav.navigate(target) {
                                popUpTo(FudAIRoutes.HOME) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    hazeState = hazeState
                )
            }
        }
    }
}

internal fun NavHostController.current(): String? = currentBackStackEntry?.destination?.route
