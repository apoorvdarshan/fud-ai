package com.apoorvdarshan.calorietracker.ui.about

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
fun AboutScreen(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("About", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Share / Rate / Contact / Donate / version land here. (placeholder)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
