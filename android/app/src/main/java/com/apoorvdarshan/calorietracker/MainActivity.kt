package com.apoorvdarshan.calorietracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.apoorvdarshan.calorietracker.ui.navigation.FudAINavHost
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Support --reset-onboarding launch flag (parallel to iOS CLAUDE.md convention).
        if (intent?.getBooleanExtra("reset_onboarding", false) == true) {
            runBlocking { (application as FudAIApp).container.prefs.setOnboardingCompleted(false) }
        }

        val container = (application as FudAIApp).container
        // Dev-only seeders for verifying the Progress tab UI without polluting Health Connect.
        // adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez seed_test_data true
        // adb shell am start -n com.apoorvdarshan.calorietracker/.MainActivity --ez restore_real_data true
        if (intent?.getBooleanExtra("seed_test_data", false) == true) {
            runBlocking { container.testDataSeeder.seedYear() }
        }
        if (intent?.getBooleanExtra("restore_real_data", false) == true) {
            runBlocking { container.testDataSeeder.restore() }
        }
        val startOnboarding = runBlocking { !container.prefs.hasCompletedOnboarding.first() }

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
