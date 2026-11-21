package com.apoorvdarshan.calorietracker.widget

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
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
import com.apoorvdarshan.calorietracker.models.WidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Process-wide recording phase. It is a flow so a live widget composition recomposes on its own,
 * and [set] also asks Glance to refresh (which starts a new composition if the old one timed out).
 */
object MealVoiceState {
    enum class Phase { IDLE, RECORDING, PROCESSING }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val flow: StateFlow<Phase> = _phase
    val phase: Phase get() = _phase.value

    /** Calories of the meal just logged, shown in green in the button for a few seconds. */
    private val _added = MutableStateFlow<Int?>(null)
    val added: StateFlow<Int?> = _added

    /** The last failure, shown as a red mark in the ring for a few seconds — feedback
     *  that survives a missing notification permission. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Bumped on every feedback, so an older timer cannot erase a newer result. */
    private var feedbackGeneration = 0L

    fun showAdded(context: Context, displayCalories: Int) {
        // A retry that succeeds supersedes the failure mark still on its timer.
        _failure.value = null
        _added.value = displayCalories
        val appContext = context.applicationContext
        val generation = ++feedbackGeneration
        scope.launch {
            runCatching { MealVoiceAppWidget().updateAll(appContext) }
            delay(ADDED_VISIBLE_MS)
            if (generation != feedbackGeneration) return@launch
            _added.value = null
            runCatching { MealVoiceAppWidget().updateAll(appContext) }
        }
    }

    fun set(context: Context, phase: Phase) {
        _phase.value = phase
        // IDLE keeps the marks (they were just set); a new attempt clears them.
        if (phase != Phase.IDLE) {
            _added.value = null
            _failure.value = null
        }
        val appContext = context.applicationContext
        scope.launch { runCatching { MealVoiceAppWidget().updateAll(appContext) } }
    }

    fun showFailure(context: Context, message: String) {
        _failure.value = message
        val appContext = context.applicationContext
        val generation = ++feedbackGeneration
        scope.launch {
            runCatching { MealVoiceAppWidget().updateAll(appContext) }
            delay(ADDED_VISIBLE_MS)
            if (generation != feedbackGeneration) return@launch
            _failure.value = null
            runCatching { MealVoiceAppWidget().updateAll(appContext) }
        }
    }
}

/**
 * One-tap voice meal logging. Tap to start recording, tap again to stop; the meal is analysed and
 * saved in the background. Shows today's calories against the goal inside a progress ring.
 */
class MealVoiceAppWidget : FudGlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshots = PreferencesStore(context).widgetSnapshot
            .map { it?.takeUnless { snapshot -> snapshot.isStale } ?: WidgetSnapshot.empty() }
        val initial = runCatching { snapshots.first() }.getOrDefault(WidgetSnapshot.empty())

        provideContent {
            val snapshot by snapshots.collectAsState(initial)
            val phase by MealVoiceState.flow.collectAsState()
            val added by MealVoiceState.added.collectAsState()
            val failed = MealVoiceState.failure.collectAsState().value != null
            GlanceTheme {
                MealVoiceContent(snapshot, phase, added, failed)
            }
        }
    }

    companion object {
        /** Below this width the widget collapses to the single-cell layout (ring only). */
        const val WIDE_MIN_WIDTH_DP = 130
    }
}

class MealVoiceWidgetReceiver : FudGlanceAppWidgetReceiver() {
    override val glanceAppWidget = MealVoiceAppWidget()
}

private const val CORNER_DP = 26f
private const val ADDED_VISIBLE_MS = 6_000L
/** Height the wide layout's calories block needs (big number plus the /goal line). */
private const val WIDE_TEXT_BLOCK_DP = 52f

@Composable
private fun MealVoiceContent(
    snapshot: WidgetSnapshot,
    phase: MealVoiceState.Phase,
    added: Int?,
    failed: Boolean
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val recording = phase == MealVoiceState.Phase.RECORDING
    val processing = phase == MealVoiceState.Phase.PROCESSING
    val start = WidgetTheme.themeStart(snapshot.themeStartHex)
    val end = WidgetTheme.themeEnd(snapshot.themeEndHex)
    val density = Resources.getSystem().displayMetrics.density
    val label = context.getString(R.string.meal_voice_widget_label)
    val wide = size.width.value >= MealVoiceAppWidget.WIDE_MIN_WIDTH_DP

    val pad = if (wide) 12f else 6f
    val ringDp = if (wide) {
        // Leave the two-line calories block its space inside the row: clipping the
        // ring beats clipping the totals a short wide widget exists to show.
        val available = size.height.value - pad * 2
        (available - WIDE_TEXT_BLOCK_DP).coerceIn(40f, 110f)
    } else {
        (minOf(size.width.value, size.height.value) - pad * 2).coerceIn(40f, 120f)
    }
    val ring = mealVoiceBadgeBitmap(
        sizePx = (ringDp * density).toInt(),
        progress = snapshot.calorieProgress.toFloat(),
        recording = recording,
        processing = processing,
        startRgb = start,
        endRgb = end,
        addedText = when {
            recording || processing -> null
            added != null -> "+$added"
            failed -> "!"
            else -> null
        },
        failed = !recording && !processing && failed
    )

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetTheme.backgroundProvider)
            .cornerRadius(CORNER_DP.dp)
            .clickable(actionStartActivity<MealVoiceToggleActivity>()),
        contentAlignment = Alignment.Center
    ) {
        if (recording) {
            // Diffuse red glow along the widget's outline.
            val glow = edgeGlowBitmap(
                widthPx = (size.width.value * density).toInt().coerceAtMost(1400),
                heightPx = (size.height.value * density).toInt().coerceAtMost(1400),
                cornerPx = CORNER_DP * density,
                glowPx = 9f * density
            )
            Image(
                provider = ImageProvider(glow),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxSize()
            )
        }

        if (!wide) {
            Image(
                provider = ImageProvider(ring),
                contentDescription = label,
                modifier = GlanceModifier.size(ringDp.dp)
            )
        } else {
            val gap = 12f
            val textWidthDp = (size.width.value - pad * 2 - ringDp - gap).coerceAtLeast(30f)
            Row(
                modifier = GlanceModifier.padding(pad.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(ring),
                    contentDescription = label,
                    modifier = GlanceModifier.size(ringDp.dp)
                )
                Spacer(GlanceModifier.width(gap.dp))
                CaloriesText(snapshot, textWidthDp, size.height.value)
            }
        }
    }
}

/** Big "eaten" number with the small, dimmer "/ goal unit" underneath; fonts fit the available width. */
@Composable
private fun CaloriesText(snapshot: WidgetSnapshot, widthDp: Float, heightDp: Float) {
    val context = LocalContext.current
    val numbers = NumberFormat.getIntegerInstance()
    val eaten = numbers.format(snapshot.displayCalories)
    val unit = context.getString(if (snapshot.usesKilojoules) R.string.unit_kj else R.string.unit_kcal)
    val goalFull = "/ ${numbers.format(snapshot.displayCalorieGoal)} $unit"
    val goalShort = "/ ${numbers.format(snapshot.displayCalorieGoal)}"

    // Roughly 0.6em per character; shrink to fit, never below a readable floor.
    val bigSp = minOf(heightDp * 0.32f, widthDp / (eaten.length * 0.62f)).coerceIn(16f, 36f)
    val goalSp = (bigSp * 0.5f).coerceIn(11f, 16f)
    val goal = if (goalFull.length * goalSp * 0.58f <= widthDp) goalFull else goalShort

    Column(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = eaten,
            maxLines = 1,
            style = TextStyle(
                color = WidgetTheme.themeTextProvider(snapshot.themeStartHex),
                fontWeight = FontWeight.Bold,
                fontSize = bigSp.sp
            )
        )
        Text(
            text = goal,
            maxLines = 1,
            style = TextStyle(
                color = WidgetTheme.secondaryTextProvider,
                fontWeight = FontWeight.Medium,
                fontSize = goalSp.sp
            )
        )
    }
}
