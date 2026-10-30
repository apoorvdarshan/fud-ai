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
import com.apoorvdarshan.calorietracker.ui.util.valueWithUnit
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.first

class ProteinAppWidget : FudGlanceAppWidget() {

    // Exact so LocalSize reports the real widget dimensions (see CalorieAppWidget).
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Never let a data-read failure leave the widget stuck on the loading layout — fall back to
        // an empty snapshot so provideContent always runs and the widget renders.
        val snapshot = runCatching {
            PreferencesStore(context).widgetSnapshot.first()?.takeUnless { it.isStale }
        }.getOrNull() ?: WidgetSnapshot.empty()

        provideContent {
            GlanceTheme {
                ProteinWidgetContent(snapshot)
            }
        }
    }

    companion object {
        val SMALL_SIZE = DpSize(140.dp, 140.dp)
        val MEDIUM_SIZE = DpSize(280.dp, 140.dp)
    }
}

class ProteinWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = ProteinAppWidget()
}

@Composable
private fun ProteinWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (size.width < ProteinAppWidget.MEDIUM_SIZE.width) {
            ProteinSmall(snapshot)
        } else {
            ProteinMedium(snapshot)
        }
    }
}

@Composable
private fun ProteinSmall(snapshot: WidgetSnapshot) {
    val nutrient = snapshot.primaryHomeNutrient
    val remaining = maxOf(0.0, nutrient.goal - nutrient.value)
    val size = LocalSize.current
    val contentW = size.width.value - 28f
    val contentH = size.height.value - 28f
    val gaugeW = minOf(contentW, (contentH - 44f) / 0.58f).toInt().coerceAtLeast(80)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(iconRes = R.drawable.ic_widget_bolt, label = nutrient.label)
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center
        ) {
            SpeedometerWithCenter(
                progress = nutrient.progress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = LocalContext.current.valueWithUnit(MacroValueFormatter.display(nutrient.value), nutrient.unit),
                centerSmall = "/ " + LocalContext.current.valueWithUnit(MacroValueFormatter.display(nutrient.goal), nutrient.unit)
            )
        }
        Text(
            text = LocalContext.current.getString(R.string.widget_left_format, LocalContext.current.valueWithUnit(MacroValueFormatter.display(remaining), nutrient.unit)),
            style = TextStyle(
                color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun ProteinMedium(snapshot: WidgetSnapshot) {
    val nutrient = snapshot.primaryHomeNutrient
    val remaining = maxOf(0.0, nutrient.goal - nutrient.value)
    val size = LocalSize.current
    val contentH = size.height.value - 28f
    val gaugeW = minOf(size.width.value * 0.40f, (contentH - 22f) / 0.58f).toInt().coerceAtLeast(90)
    val barH = (contentH - 58f).toInt().coerceAtLeast(36)

    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpeedometerWithCenter(
                progress = nutrient.progress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = LocalContext.current.valueWithUnit(MacroValueFormatter.display(nutrient.value), nutrient.unit),
                centerSmall = "/ " + LocalContext.current.valueWithUnit(MacroValueFormatter.display(nutrient.goal), nutrient.unit)
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = LocalContext.current.getString(R.string.widget_left_format, LocalContext.current.valueWithUnit(MacroValueFormatter.display(remaining), nutrient.unit)),
                style = TextStyle(
                    color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.width(10.dp))
        Box(modifier = GlanceModifier.defaultWeight()) {
            NutrientBarsRow(
                snapshot,
                barHeightDp = barH,
                barWidthDp = (barH * 0.26f).toInt().coerceIn(11, 18),
                valueFontSp = 15
            )
        }
    }
}
