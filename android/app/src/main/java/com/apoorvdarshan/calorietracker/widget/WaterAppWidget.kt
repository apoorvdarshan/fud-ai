package com.apoorvdarshan.calorietracker.widget

import android.content.Context
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.apoorvdarshan.calorietracker.MainActivity
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.first

class WaterAppWidget : FudGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = runCatching {
            PreferencesStore(context).widgetSnapshot.first()?.let {
                if (it.isStale) it.emptyForToday() else it
            }
        }.getOrNull() ?: WidgetSnapshot.empty()

        provideContent {
            GlanceTheme {
                WaterWidgetContent(snapshot)
            }
        }
    }

    companion object {
        val SMALL_SIZE = DpSize(140.dp, 140.dp)
    }
}

class WaterWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = WaterAppWidget()
}

@Composable
private fun WaterWidgetContent(snapshot: WidgetSnapshot) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(22.dp)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (snapshot.waterTrackingEnabled) {
            WaterProgressContent(snapshot)
        } else {
            WaterDisabledContent()
        }
    }
}

@Composable
private fun WaterProgressContent(snapshot: WidgetSnapshot) {
    val context = LocalContext.current
    val size = LocalSize.current
    val contentW = size.width.value - 28f
    val contentH = size.height.value - 28f
    val gaugeW = minOf(contentW, (contentH - 44f) / 0.58f).toInt().coerceAtLeast(80)

    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(iconRes = R.drawable.ic_widget_water, label = context.getString(R.string.widget_water))
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center
        ) {
            SpeedometerWithCenter(
                progress = snapshot.waterProgress.toFloat(),
                gaugeWidthDp = gaugeW,
                startHex = snapshot.themeStartHex,
                endHex = snapshot.themeEndHex,
                centerLarge = snapshot.waterUnit.displayValue(snapshot.waterCurrentMl),
                centerSmall = "/ ${snapshot.waterUnit.format(snapshot.waterGoalMl)}"
            )
        }
        Text(
            text = context.getString(R.string.widget_left_format, snapshot.waterUnit.format(snapshot.waterRemaining)),
            style = TextStyle(
                color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        )
    }
}

@Composable
private fun WaterDisabledContent() {
    val context = LocalContext.current
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_water),
                contentDescription = null,
                modifier = GlanceModifier.size(30.dp)
            )
            Spacer(modifier = GlanceModifier.height(8.dp))
            Text(
                text = context.getString(R.string.settings_water_tracking),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = context.getString(R.string.widget_enable_in_app),
                style = TextStyle(color = WidgetTheme.secondaryTextProvider, fontSize = 12.sp)
            )
        }
    }
}
