package com.apoorvdarshan.calorietracker.widget

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.MealVoiceService

/**
 * Invisible trampoline behind the voice-meal widget tap. Android only lets a microphone foreground
 * service start while the app has a visible window, so this transparent activity starts (or stops)
 * [MealVoiceService] and finishes immediately; Fud AI itself never comes to the foreground. It also
 * hosts the one-time microphone permission prompt.
 */
class MealVoiceToggleActivity : ComponentActivity() {

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            MealVoiceService.start(this)
        } else {
            Toast.makeText(this, R.string.meal_voice_mic_denied, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (MealVoiceState.phase) {
            MealVoiceState.Phase.RECORDING -> {
                MealVoiceService.stop(this)
                finish()
            }
            MealVoiceState.Phase.PROCESSING -> finish()
            MealVoiceState.Phase.IDLE -> {
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) {
                    MealVoiceService.start(this)
                    finish()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}
