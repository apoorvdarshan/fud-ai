package com.apoorvdarshan.calorietracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.services.voice.VoiceEntryPoints
import com.apoorvdarshan.calorietracker.ui.theme.AppThemeColor
import kotlinx.coroutines.flow.first

/** A one-tap button on the home screen that opens a voice conversation with Fud AI. */
class VoiceAssistantAppWidget : FudGlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val theme = runCatching { AppThemeColor.fromKey(PreferencesStore(context).appThemeColor.first()) }
            .getOrDefault(AppThemeColor.FUD_PINK)
        val label = context.getString(R.string.voice_talk_to_fud_ai)
        provideContent { VoiceWidgetContent(context, theme.start, label) }
    }
}

class VoiceAssistantWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = VoiceAssistantAppWidget()
}

private val WidgetDark = Color(0xFF101116)

@Composable
private fun VoiceWidgetContent(context: Context, tint: Color, label: String) {
    val showLabel = LocalSize.current.width >= 120.dp
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(28.dp)
            .background(ColorProvider(day = WidgetDark, night = WidgetDark))
            .clickable(actionStartActivity(VoiceEntryPoints.launchIntent(context)))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                provider = ImageProvider(R.drawable.ic_voice_orb),
                contentDescription = if (showLabel) null else label,
                colorFilter = ColorFilter.tint(ColorProvider(day = tint, night = tint)),
                modifier = GlanceModifier.size(34.dp)
            )
            if (showLabel) {
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    text = label,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(day = Color.White, night = Color.White),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                )
            }
        }
    }
}
