package com.apoorvdarshan.calorietracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.apoorvdarshan.calorietracker.ui.navigation.FudAINavHost
import com.apoorvdarshan.calorietracker.ui.theme.FudAITheme
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Support --reset-onboarding launch flag (parallel to iOS CLAUDE.md convention).
        if (intent?.getBooleanExtra("reset_onboarding", false) == true) {
            runBlocking { (application as FudAIApp).container.prefs.setOnboardingCompleted(false) }
        }

        val container = (application as FudAIApp).container
        val startOnboarding = runBlocking { !container.prefs.hasCompletedOnboarding.first() }

        setContent {
            FudAITheme {
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
