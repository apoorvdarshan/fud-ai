package com.apoorvdarshan.calorietracker

import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.apoorvdarshan.calorietracker.ui.navigation.FudAINavHost
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the system swaps the splash theme
        // back to Theme.FudAI before the first frame, preventing a white flash
        // on cold start. The splash itself shows the launcher icon on the
        // app's cream/dark background (see values/themes.xml).
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Support --reset-onboarding launch flag (parallel to iOS CLAUDE.md convention).
        if (intent?.getBooleanExtra("reset_onboarding", false) == true) {
            runBlocking { (application as FudAIApp).container.prefs.setOnboardingCompleted(false) }
            intent.removeExtra("reset_onboarding")
        }

        val container = (application as FudAIApp).container
        // Dev-only seeders for verifying the Progress tab UI without polluting Health Connect.
        // adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez seed_test_data true
        // adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez restore_real_data true
        // Extras are removed after handling so Activity.recreate() (used by Delete All
        // Data) doesn't re-fire the same flag on the next onCreate.
        if (intent?.getBooleanExtra("seed_test_data", false) == true) {
            runBlocking { container.testDataSeeder.seedYear() }
            intent.removeExtra("seed_test_data")
        }
        if (intent?.getBooleanExtra("restore_real_data", false) == true) {
            runBlocking { container.testDataSeeder.restore() }
            intent.removeExtra("restore_real_data")
        }
        val startOnboarding = runBlocking { !container.prefs.hasCompletedOnboarding.first() }

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
            val appearance by container.prefs.appearanceMode.collectAsState(initial = "system")
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (appearance) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            FudAITheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FudAINavHost(container = container, startOnboarding = startOnboarding)
                }
            }
        }
    }
}
