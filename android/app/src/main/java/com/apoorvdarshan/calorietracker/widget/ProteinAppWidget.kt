package com.apoorvdarshan.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
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
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.flow.first

class ProteinAppWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SIZE, MEDIUM_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PreferencesStore(context)
        val snapshot = prefs.widgetSnapshot.first()
            ?.takeUnless { it.isStale }
            ?: WidgetSnapshot.empty()

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

class ProteinWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ProteinAppWidget()
}

@Composable
private fun ProteinWidgetContent(snapshot: WidgetSnapshot) {
    val size = LocalSize.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(20.dp)
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
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Protein",
                style = TextStyle(
                    color = WidgetTheme.secondaryTextProvider,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        Box(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${snapshot.protein}g",
                    style = TextStyle(
                        color = WidgetTheme.calorieProvider,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    )
                )
                Text(
                    text = "of ${snapshot.proteinGoal}g",
                    style = TextStyle(color = WidgetTheme.secondaryTextProvider, fontSize = 11.sp)
                )
            }
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        LinearProgressIndicator(
            progress = snapshot.proteinProgress.toFloat(),
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = WidgetTheme.calorieProvider,
            backgroundColor = WidgetTheme.calorieTrackProvider
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = "${snapshot.proteinRemaining}g left",
            style = TextStyle(
                color = WidgetTheme.secondaryTextProvider,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp
            )
        )
    }
}

@Composable
private fun ProteinMedium(snapshot: WidgetSnapshot) {
    Row(modifier = GlanceModifier.fillMaxSize()) {
        Column(
            modifier = GlanceModifier.fillMaxHeight().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Protein",
                style = TextStyle(
                    color = WidgetTheme.secondaryTextProvider,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp
                )
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = "${snapshot.protein}g",
                style = TextStyle(
                    color = WidgetTheme.calorieProvider,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
                )
            )
            Text(
                text = "of ${snapshot.proteinGoal}g",
                style = TextStyle(color = WidgetTheme.secondaryTextProvider, fontSize = 11.sp)
            )
            Spacer(modifier = GlanceModifier.height(6.dp))
            LinearProgressIndicator(
                progress = snapshot.proteinProgress.toFloat(),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = WidgetTheme.calorieProvider,
                backgroundColor = WidgetTheme.calorieTrackProvider
            )
        }
        Spacer(modifier = GlanceModifier.width(14.dp))
        Column(
            modifier = GlanceModifier.fillMaxHeight().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProteinSideRow("Calories", snapshot.calories, snapshot.calorieGoal, snapshot.calorieProgress.toFloat(), unit = "")
            Spacer(modifier = GlanceModifier.height(8.dp))
            ProteinSideRow("Carbs", snapshot.carbs, snapshot.carbsGoal, snapshot.carbsProgress.toFloat(), unit = "g")
            Spacer(modifier = GlanceModifier.height(8.dp))
            ProteinSideRow("Fat", snapshot.fat, snapshot.fatGoal, snapshot.fatProgress.toFloat(), unit = "g")
        }
    }
}

@Composable
private fun ProteinSideRow(label: String, value: Int, goal: Int, progress: Float, unit: String) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = TextStyle(
                    color = WidgetTheme.secondaryTextProvider,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                ),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = "${value}${unit} / ${goal}${unit}",
                style = TextStyle(
                    color = WidgetTheme.primaryTextProvider,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(3.dp))
        LinearProgressIndicator(
            progress = progress,
            modifier = GlanceModifier.fillMaxWidth().height(5.dp),
            color = WidgetTheme.calorieProvider,
            backgroundColor = WidgetTheme.calorieTrackProvider
        )
    }
}
