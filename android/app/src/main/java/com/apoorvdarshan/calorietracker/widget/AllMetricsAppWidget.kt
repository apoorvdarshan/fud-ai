package com.apoorvdarshan.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.first

/**
 * Single "all today's metrics" widget — calories ring + protein / carbs / fat together, so users
 * can see everything at a glance from one widget instead of adding the separate Calorie and Protein
 * widgets. Reuses the shared building blocks (RingWithCenter, CapsuleMacroRow, WidgetHeader).
 */
class AllMetricsAppWidget : FudGlanceAppWidget() {

    // Exact so LocalSize reports the real widget dimensions (see CalorieAppWidget).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Never let a data-read failure leave the widget stuck on the loading layout.
        val snapshot = runCatching {
            PreferencesStore(context).widgetSnapshot.first()?.takeUnless { it.isStale }
        }.getOrNull() ?: WidgetSnapshot.empty()

        provideContent {
            GlanceTheme {
                AllMetricsContent(snapshot)
            }
        }
    }

    companion object {
        val WIDE_SIZE = DpSize(250.dp, 110.dp)
        val TALL_SIZE = DpSize(250.dp, 220.dp)
    }
}

class AllMetricsWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = AllMetricsAppWidget()
}

@Composable
private fun AllMetricsContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (size.height < AllMetricsAppWidget.TALL_SIZE.height) {
            AllMetricsWide(snapshot)
        } else {
            AllMetricsTall(snapshot)
        }
    }
}

@Composable
private fun AllMetricsWide(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val contentH = size.height.value - 28f
    val gaugeW = minOf(size.width.value * 0.36f, (contentH - 20f) / 0.58f).toInt().coerceAtLeast(80)
    val barH = (contentH - 54f).toInt().coerceAtLeast(30)

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedometerWithCenter(
                progress = snapshot.calorieProgress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = snapshot.displayCalories.toString(),
                centerSmall = "/ ${snapshot.displayCalorieGoal}"
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                // Same unit-aware text as the tall layout, so kJ numbers name their unit.
                text = energyLeftText(snapshot),
                style = TextStyle(
                    color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.width(10.dp))
        Box(modifier = GlanceModifier.defaultWeight()) {
            NutrientBarsRow(
                snapshot,
                barHeightDp = barH,
                barWidthDp = (barH * 0.28f).toInt().coerceIn(10, 18),
                valueFontSp = 14
            )
        }
    }
}

@Composable
private fun AllMetricsTall(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    val contentW = size.width.value - 28f
    val contentH = size.height.value - 28f
    val gaugeW = minOf(contentW * 0.60f, contentH * 0.42f / 0.58f).toInt().coerceAtLeast(96)
    val barH = (contentH - 34f - gaugeW * 0.58f - 66f).toInt().coerceAtLeast(40)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(iconRes = R.drawable.ic_widget_flame, label = LocalContext.current.getString(R.string.widget_today))
        Spacer(modifier = GlanceModifier.height(4.dp))
        Box(
            modifier = GlanceModifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            SpeedometerWithCenter(
                progress = snapshot.calorieProgress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = snapshot.displayCalories.toString(),
                centerSmall = "/ ${snapshot.displayCalorieGoal}"
            )
        }
        Box(
            modifier = GlanceModifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = energyLeftText(snapshot),
                style = TextStyle(
                    color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        NutrientBarsRow(
            snapshot,
            barHeightDp = barH,
            barWidthDp = (barH * 0.26f).toInt().coerceIn(11, 18),
            valueFontSp = 15
        )
    }
}
