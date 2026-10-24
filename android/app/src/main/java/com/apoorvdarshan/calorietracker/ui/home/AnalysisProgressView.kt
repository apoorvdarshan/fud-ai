package com.apoorvdarshan.calorietracker.ui.home

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.ai.analysisClockMillis
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressState
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.delay

/**
 * The progress part of the analyzing overlay: the current stage, the elapsed time and —
 * only when the local history supports it — how long this request usually takes.
 *
 * There is no percentage and no countdown: the providers answer with one complete
 * document, so a percentage would be a guess. The estimate is a band of durations this
 * device measured itself, and it disappears once the request runs past it.
 */
@Composable
fun AnalysisProgressView(
    progress: AnalysisProgressState,
    modifier: Modifier = Modifier
) {
    // The elapsed time ticks once per second so the user can see that something is
    // happening even when the provider is slow. Keyed on the start time, so a new
    // analysis restarts the ticker instead of continuing the old one.
    val elapsedSeconds by produceState(initialValue = 0L, progress.startedAtMillis) {
        while (true) {
            value = progress.elapsedSeconds(analysisClockMillis())
            delay(1_000)
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = stageLabel(progress),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            // Full on-background instead of the pink accent: the accent only reaches
            // about 3:1 on the light theme, below WCAG AA for this text size.
            color = MaterialTheme.colorScheme.onBackground,
            // Only the stage line is a live region: the elapsed time ticks every
            // second and would spam the screen reader.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Text(
            text = stringResource(R.string.analysis_elapsed_seconds, elapsedSeconds.toInt()),
            fontSize = 14.sp,
            // 75 % of on-surface stays above 4.5:1 on both the light and dark theme.
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        // The estimate is only shown while it still fits: once the request runs past
        // the upper end of the band, the hint would be a lie.
        val estimate = progress.estimateSeconds
        if (estimate != null && progress.estimateStillApplies(elapsedSeconds)) {
            Text(
                text = estimateLabel(estimate),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}

/**
 * The label for the current stage.
 *
 * The stages only describe work that really happened: a barcode lookup is no AI call,
 * so it never shows an AI stage, and an on-device model does not wait for a provider.
 */
@Composable
internal fun stageLabel(progress: AnalysisProgressState): String {
    val labelRes = analysisStageLabelRes(progress)
    return if (labelRes == R.string.analysis_stage_fallback_attempt) {
        stringResource(labelRes, progress.attempt)
    } else {
        stringResource(labelRes)
    }
}

/**
 * The "usually about N s" hint for a measured duration band. The caller shows it only
 * while it still fits the running request.
 */
@Composable
internal fun estimateLabel(estimate: IntRange): String =
    if (estimate.first == estimate.last) {
        stringResource(R.string.analysis_estimate_single, estimate.first)
    } else {
        stringResource(R.string.analysis_estimate_range, estimate.first, estimate.last)
    }

/**
 * The indeterminate spinner used while a stage is running. When the system turned
 * animations off, a static icon takes its place: the stage and the elapsed time still
 * show the progress, without a spinning animation.
 */
@Composable
internal fun AnalysisProgressIndicator(
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    if (animate) {
        CircularProgressIndicator(
            color = AppColors.Calorie,
            strokeWidth = 4.dp,
            modifier = modifier.size(40.dp)
        )
    } else {
        Icon(
            imageVector = Icons.Filled.HourglassEmpty,
            contentDescription = null,
            tint = AppColors.Calorie,
            modifier = modifier.size(36.dp)
        )
    }
}

/** False when the system turned animations off (Developer options or accessibility). */
internal fun systemAnimationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
