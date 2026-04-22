package com.apoorvdarshan.calorietracker.ui.progress

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
fun ProgressScreen(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Weight chart + stats land here. (placeholder)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
