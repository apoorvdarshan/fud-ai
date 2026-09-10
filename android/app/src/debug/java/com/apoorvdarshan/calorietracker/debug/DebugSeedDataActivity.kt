package com.apoorvdarshan.calorietracker.debug

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.apoorvdarshan.calorietracker.FudAIApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ADB-only entry point that is compiled into the debug APK and absent from release builds. */
class DebugSeedDataActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DebugDemoDataSeeder(
                        application = application as FudAIApp
                    ).seed(futureDays = intent.getIntExtra("future_days", 0))
                }
            }.onSuccess { report ->
                Log.i(TAG, "Demo seed complete: $report")
                Toast.makeText(
                    this@DebugSeedDataActivity,
                    "Debug demo data ready (${report.foodEntries} food logs, through ${report.throughDate})",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Log.e(TAG, "Demo seed failed", error)
                Toast.makeText(
                    this@DebugSeedDataActivity,
                    "Debug seed failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    private companion object {
        const val TAG = "FudAiDebugSeeder"
    }
}
