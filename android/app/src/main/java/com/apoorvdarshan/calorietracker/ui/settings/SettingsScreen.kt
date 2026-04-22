package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.apoorvdarshan.calorietracker.AppContainer

@Composable
fun SettingsScreen(container: AppContainer, nav: NavHostController) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text(
            "AI Provider, Speech, Notifications, Health Connect, Goals, Delete Data land here. (placeholder)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
