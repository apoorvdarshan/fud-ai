package com.apoorvdarshan.calorietracker.ui.home

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
fun HomeScreen(container: AppContainer) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Today's food log lands here. (placeholder)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
