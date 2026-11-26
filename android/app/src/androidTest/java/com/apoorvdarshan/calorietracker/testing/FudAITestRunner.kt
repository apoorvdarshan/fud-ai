package com.apoorvdarshan.calorietracker.testing

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.runner.AndroidJUnitRunner
import com.apoorvdarshan.calorietracker.BuildConfig
import com.apoorvdarshan.calorietracker.FudAIApp
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressReporter
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import java.util.Locale
import kotlinx.coroutines.awaitCancellation

/** Lives in the test APK only; normal Debug and Release launches never use it. */
class FudAITestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        requireTestTarget(requireNotNull(context))
        return super.newApplication(cl, FudAITestApplication::class.java.name, context)
    }
}

class FudAITestApplication : FudAIApp() {
    override val backgroundServicesEnabled = false
    @Volatile var holdFoodTextAnalysis = false

    override suspend fun analyzeFoodText(description: String, progress: AnalysisProgressReporter): FoodAnalysis {
        if (holdFoodTextAnalysis) awaitCancellation()
        return super.analyzeFoodText(description, progress)
    }

    override fun onCreate() {
        requireTestTarget(this)
        Locale.setDefault(Locale.US)
        super.onCreate()
        getSharedPreferences("welcome", MODE_PRIVATE).edit().putBoolean("seenMascotCafe", true).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"))
    }
}

internal fun requireTestTarget(context: Context) {
    check(BuildConfig.DEBUG && context.packageName == "com.apoorvdarshan.calorietracker.debug") {
        "Instrumentation must target the dedicated Fud AI Debug package."
    }
    check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
        Build.FINGERPRINT.startsWith("generic") || Build.MODEL.startsWith("sdk_gphone")) {
        "Run this isolated suite on an Android emulator, not a personal phone."
    }
}
