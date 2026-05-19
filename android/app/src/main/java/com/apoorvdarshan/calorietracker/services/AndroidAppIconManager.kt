package com.apoorvdarshan.calorietracker.services

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor

object AndroidAppIconManager {
    private const val TAG = "AndroidAppIconManager"
    private const val COMPONENT_NAMESPACE = "com.apoorvdarshan.calorietracker"

    private val launcherActivities = mapOf(
        AppThemeColor.FUD_PINK to "FudPinkLauncherActivity",
        AppThemeColor.RED to "RedLauncherActivity",
        AppThemeColor.ORANGE to "OrangeLauncherActivity",
        AppThemeColor.GREEN to "GreenLauncherActivity",
        AppThemeColor.MINT to "MintLauncherActivity",
        AppThemeColor.TEAL to "TealLauncherActivity",
        AppThemeColor.BLUE to "BlueLauncherActivity",
        AppThemeColor.PURPLE to "PurpleLauncherActivity"
    )

    fun apply(context: Context, themeColor: AppThemeColor) {
        runCatching {
            applyLauncherIcon(context, themeColor)
        }.onFailure { error ->
            Log.w(TAG, "Unable to apply launcher icon color", error)
        }
    }

    private fun applyLauncherIcon(context: Context, themeColor: AppThemeColor) {
        val selectedLauncher = launcherActivities[themeColor] ?: launcherActivities.getValue(AppThemeColor.FUD_PINK)
        val packageManager = context.packageManager
        val packageName = context.packageName

        if (launcherActivities.values.all { launcher ->
                val desiredState = if (launcher == selectedLauncher) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                packageManager.getComponentEnabledSetting(
                    ComponentName(packageName, "$COMPONENT_NAMESPACE.$launcher")
                ) == desiredState
            }
        ) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flags = PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS
            val settings = launcherActivities.values.map { launcher ->
                PackageManager.ComponentEnabledSetting(
                    ComponentName(packageName, "$COMPONENT_NAMESPACE.$launcher"),
                    if (launcher == selectedLauncher) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    flags
                )
            }
            packageManager.setComponentEnabledSettings(settings)
            return
        }

        setLauncherState(packageManager, packageName, selectedLauncher, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        launcherActivities.values
            .filterNot { it == selectedLauncher }
            .forEach { launcher ->
                setLauncherState(packageManager, packageName, launcher, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
            }
    }

    private fun setLauncherState(
        packageManager: PackageManager,
        packageName: String,
        launcher: String,
        desiredState: Int
    ) {
        val component = ComponentName(packageName, "$COMPONENT_NAMESPACE.$launcher")
        if (packageManager.getComponentEnabledSetting(component) == desiredState) return

        packageManager.setComponentEnabledSetting(
            component,
            desiredState,
            PackageManager.DONT_KILL_APP
        )
    }
}
