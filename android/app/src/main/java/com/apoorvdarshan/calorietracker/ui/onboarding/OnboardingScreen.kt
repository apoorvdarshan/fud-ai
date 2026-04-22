package com.apoorvdarshan.calorietracker.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.UserProfile
import kotlinx.coroutines.launch

/**
 * Placeholder onboarding. Real 15-step flow lands in a follow-up commit.
 * For now this just writes a default profile and completes so the rest of
 * the app is reachable for testing.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Fud AI", style = MaterialTheme.typography.displayMedium)
        Text("Welcome.", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Placeholder onboarding — real 15-step flow coming soon.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Button(
            onClick = {
                scope.launch {
                    container.profileRepository.save(UserProfile.Default)
                    container.prefs.setOnboardingCompleted(true)
                    container.weightRepository.seedInitialWeightIfEmpty(UserProfile.Default.weightKg)
                    onComplete()
                }
            },
            modifier = Modifier.padding(top = 32.dp)
        ) { Text("Continue") }
    }
}
