package com.apoorvdarshan.calorietracker.widget

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.ui.util.valueWithUnit
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.MacroValueFormatter
import com.apoorvdarshan.calorietracker.models.WidgetNutrient
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.first

class CalorieAppWidget : FudGlanceAppWidget() {

    // Exact so LocalSize reports the real widget dimensions — the gauge and
    // bars scale to fill instead of floating at bucket-minimum size.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Never let a data-read failure leave the widget stuck on the loading layout — fall back to
        // an empty snapshot so provideContent always runs and the widget renders.
        val snapshot = runCatching {
            PreferencesStore(context).widgetSnapshot.first()?.takeUnless { it.isStale }
        }.getOrNull() ?: WidgetSnapshot.empty()

        provideContent {
            GlanceTheme {
                CalorieWidgetContent(snapshot)
            }
        }
    }

    companion object {
        val SMALL_SIZE = DpSize(140.dp, 140.dp)
        val MEDIUM_SIZE = DpSize(280.dp, 140.dp)
    }
}

class CalorieWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = CalorieAppWidget()
}

@Composable
private fun CalorieWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (size.width < CalorieAppWidget.MEDIUM_SIZE.width) {
            CalorieSmall(snapshot)
        } else {
            CalorieMedium(snapshot)
        }
    }
}

@Composable
private fun CalorieSmall(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    // Content area after the outer 14dp padding; the gauge fills whatever the
    // header (~18dp) and the bottom line (~16dp) leave over.
    val contentW = size.width.value - 28f
    val contentH = size.height.value - 28f
    val gaugeW = minOf(contentW, (contentH - 44f) / 0.58f).toInt().coerceAtLeast(80)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(iconRes = R.drawable.ic_widget_flame, label = LocalContext.current.getString(R.string.widget_today))
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
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
        Text(
            text = energyLeftText(snapshot),
            style = TextStyle(
                color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun CalorieMedium(snapshot: WidgetSnapshot) {
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
                progress = snapshot.calorieProgress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = snapshot.displayCalories.toString(),
                centerSmall = "/ ${snapshot.displayCalorieGoal}"
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = energyLeftText(snapshot),
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

// ─── Shared building blocks ────────────────────────────────────────────────

/** "N kcal left" / "N kJ left" in the energy unit the app last published. */
@Composable
internal fun energyLeftText(snapshot: WidgetSnapshot): String {
    val context = LocalContext.current
    return if (snapshot.usesKilojoules) {
        context.getString(R.string.widget_kj_left_format, snapshot.displayCaloriesRemaining)
    } else {
        context.getString(R.string.widget_kcal_left_format, snapshot.caloriesRemaining)
    }
}

@Composable
internal fun WidgetHeader(iconRes: Int, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(12.dp)
        )
        Spacer(modifier = GlanceModifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(
                color = WidgetTheme.secondaryTextProvider,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        )
    }
}

/**
 * Home-style dashed speedometer with the readout inside the dome. The gauge
 * bitmap is gaugeWidth x (0.58 * gaugeWidth); texts are centered over it.
 */
@Composable
internal fun SpeedometerWithCenter(
    progress: Float,
    gaugeWidthDp: Int,
    startHex: Int?,
    endHex: Int?,
    centerLarge: String,
    centerSmall: String
) {
    val density = Resources.getSystem().displayMetrics.density
    val sizePx = (gaugeWidthDp * density).toInt().coerceAtLeast(1)
    // Stroke and fonts scale with the gauge so bigger widgets get a
    // proportionally bigger dial, not the same dial with more air.
    val strokePx = (gaugeWidthDp * 0.085f * density).coerceAtLeast(6f)
    val bitmap = speedometerBitmap(
        diameterPx = sizePx,
        progress = progress,
        strokeWidthPx = strokePx,
        startRgb = WidgetTheme.themeStart(startHex),
        endRgb = WidgetTheme.themeEnd(endHex)
    )
    val gaugeHeightDp = (gaugeWidthDp * 0.58f).toInt()
    val centerLargeFontSize = gaugeCenterFontSizeSp(gaugeWidthDp, centerLarge).sp
    val centerSmallFontSize = gaugeSecondaryFontSizeSp(gaugeWidthDp, centerSmall).sp

    Box(
        modifier = GlanceModifier.size(gaugeWidthDp.dp, gaugeHeightDp.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize()
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLarge,
                style = TextStyle(
                    color = WidgetTheme.themeTextProvider(startHex),
                    fontWeight = FontWeight.Bold,
                    fontSize = centerLargeFontSize
                )
            )
            Text(
                text = centerSmall,
                style = TextStyle(
                    color = WidgetTheme.secondaryTextProvider,
                    fontSize = centerSmallFontSize
                )
            )
        }
    }
}

/**
 * Keeps the primary readout inside the clear center of the semicircle. Values
 * can be much wider than calories when the Protein widget follows a selected
 * micronutrient (for example, "1234mg"), so gauge width and text length both
 * participate in sizing.
 */
internal fun gaugeCenterFontSizeSp(gaugeWidthDp: Int, text: String): Int {
    val baseSize = (gaugeWidthDp * 0.19f).toInt().coerceIn(17, 34)
    val characterCount = text.length.coerceAtLeast(1)
    val widthSafeSize = (gaugeWidthDp * 0.80f / characterCount).toInt()
    return minOf(baseSize, widthSafeSize).coerceAtLeast(10)
}

/** Goal/subtitle equivalent of [gaugeCenterFontSizeSp]. */
internal fun gaugeSecondaryFontSizeSp(gaugeWidthDp: Int, text: String): Int {
    val baseSize = (gaugeWidthDp * 0.10f).toInt().coerceIn(10, 15)
    val characterCount = text.length.coerceAtLeast(1)
    val widthSafeSize = (gaugeWidthDp * 0.90f / characterCount).toInt()
    return minOf(baseSize, widthSafeSize).coerceAtLeast(9)
}

/** Home's 4 visible nutrients as vertical fill tubes, including fixed-slot Water when enabled. */
@Composable
internal fun NutrientBarsRow(
    snapshot: WidgetSnapshot,
    barHeightDp: Int,
    barWidthDp: Int = 11,
    valueFontSp: Int = 13
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        snapshot.displayedHomeNutrients(LocalContext.current.getString(R.string.widget_water)).forEach { nutrient ->
            Box(modifier = GlanceModifier.defaultWeight()) {
                VerticalNutrientBarCell(
                    nutrient = nutrient,
                    startHex = snapshot.themeStartHex,
                    endHex = snapshot.themeEndHex,
                    barHeightDp = barHeightDp,
                    barWidthDp = barWidthDp,
                    valueFontSp = valueFontSp
                )
            }
        }
    }
}

@Composable
internal fun VerticalNutrientBarCell(
    nutrient: WidgetNutrient,
    startHex: Int?,
    endHex: Int?,
    barHeightDp: Int,
    barWidthDp: Int,
    valueFontSp: Int
) {
    val density = Resources.getSystem().displayMetrics.density
    val bitmap = verticalBarBitmap(
        widthPx = (barWidthDp * density).toInt().coerceAtLeast(2),
        heightPx = (barHeightDp * density).toInt().coerceAtLeast(2),
        progress = nutrient.progress.toFloat(),
        startRgb = WidgetTheme.themeStart(startHex),
        endRgb = WidgetTheme.themeEnd(endHex)
    )
    Column(
        modifier = GlanceModifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = MacroValueFormatter.display(nutrient.value),
            style = TextStyle(
                color = WidgetTheme.themeTextProvider(startHex),
                fontWeight = FontWeight.Bold,
                fontSize = valueFontSp.sp
            ),
            maxLines = 1
        )
        Spacer(modifier = GlanceModifier.height(3.dp))
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier.size(barWidthDp.dp, barHeightDp.dp)
        )
        Spacer(modifier = GlanceModifier.height(3.dp))
        Text(
            text = nutrient.label,
            style = TextStyle(
                color = WidgetTheme.primaryTextProvider,
                fontWeight = FontWeight.Medium,
                fontSize = (valueFontSp - 3).coerceAtLeast(10).sp
            ),
            maxLines = 1
        )
        Text(
            text = "/" + LocalContext.current.valueWithUnit(MacroValueFormatter.display(nutrient.goal), nutrient.unit),
            style = TextStyle(
                color = WidgetTheme.secondaryTextProvider,
                fontSize = (valueFontSp - 4).coerceAtLeast(9).sp
            ),
            maxLines = 1
        )
    }
}
