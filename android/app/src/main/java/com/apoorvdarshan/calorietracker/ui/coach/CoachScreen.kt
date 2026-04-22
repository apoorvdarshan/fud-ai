package com.apoorvdarshan.calorietracker.ui.coach

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.AppContainer

@Composable
fun CoachScreen(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Coach", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Multi-turn AI chat lands here. (placeholder)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
