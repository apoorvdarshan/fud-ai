package com.apoorvdarshan.calorietracker.ui.workouts

import com.apoorvdarshan.calorietracker.ui.theme.FlashSpacing
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.models.FudAILinks
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.workouts.AnimatedExerciseImage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun ExerciseDetailScreen(
    item: ExerciseItem,
    visual: ExerciseVisual,
    onBack: () -> Unit,
    onEdit: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = workoutsColors()
    var showMetrics by remember { mutableStateOf(false) }
    var showReport by rememberSaveable(item.id) { mutableStateOf(false) }
    val canReport = !UserExercise.isUserExercise(item.id) && !item.id.startsWith("custom_activity_")

    Column(modifier.testTag("workouts.detail").fillMaxSize().background(colors.background).statusBarsPadding()) {
        // Top bar: back (start) + centered title
        Box(
            Modifier.fillMaxWidth().background(colors.background).padding(vertical = 8.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = colors.accent,
                modifier = Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape).clickable { onBack() }.padding(8.dp)
            )
            Text(
                item.name,
                color = colors.charcoal,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 52.dp)
            )
            if (onEdit != null) {
                Text(
                    "Edit",
                    color = colors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        // Pinned hero over scrollable instructions (mirrors the iOS ZStack).
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = minOf(224.dp, maxHeight * 0.44f)
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = BottomNavScrollPadding)
            ) {
                item(key = "herospace") { Spacer(Modifier.height(heroHeight)) }
                item(key = "instructions") {
                    InstructionSection(
                        exerciseName = item.name,
                        instructions = item.instructions,
                        modifier = Modifier.padding(horizontal = FlashSpacing.PageInset, vertical = FlashSpacing.SectionGap)
                    )
                }
                item(key = "pad") { Spacer(Modifier.size(40.dp)) }
            }

            Hero(item = item, visual = visual, height = heroHeight, showMetrics = showMetrics,
                onToggle = { showMetrics = !showMetrics },
                onReport = if (canReport) ({ showReport = true }) else null)
        }
    }
    if (showReport && canReport) {
        ExerciseImageReportDialog(item = item, visual = visual, onDismiss = { showReport = false })
    }
}

@Composable
private fun Hero(
    item: ExerciseItem,
    visual: ExerciseVisual,
    height: Dp,
    showMetrics: Boolean,
    onToggle: () -> Unit,
    onReport: (() -> Unit)?
) {
    val colors = workoutsColors()
    // Opaque screen background so transparent PNG cutouts don't let
    // instructions show through when this pinned hero covers the scroll.
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.background)
            .border(0.5.dp, colors.hairline.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
    ) {
        AnimatedExerciseImage(visual, Modifier.fillMaxSize(), fallbackLabel = item.name)

        AnimatedVisibility(
            visible = showMetrics,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(280))
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        }

        AnimatedVisibility(
            visible = showMetrics,
            modifier = Modifier.align(Alignment.TopStart).height(height),
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(280)),
            exit = slideOutHorizontally(tween(280)) { it } + fadeOut(tween(280))
        ) {
            MetricGrid(
                item,
                Modifier.verticalScroll(rememberScrollState())
                    .padding(start = FlashSpacing.PageInset, top = 8.dp, end = 68.dp, bottom = 8.dp)
            )
        }

        Icon(
            Icons.Filled.Info,
            contentDescription = stringResource(if (showMetrics) R.string.hide_details else R.string.show_details),
            tint = colors.accent,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(FlashSpacing.MinTouchTarget)
                .clip(CircleShape)
                .background(colors.background.copy(alpha = 0.78f))
                .border(0.7.dp, colors.hairline.copy(alpha = 0.42f), CircleShape)
                .clickable { onToggle() }
                .padding(14.dp)
        )
        if (onReport != null) {
            IconButton(
                onClick = onReport,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .size(FlashSpacing.MinTouchTarget)
                    .background(colors.background.copy(alpha = 0.88f), CircleShape)
                    .border(0.7.dp, colors.hairline.copy(alpha = 0.55f), CircleShape)
                    .testTag("workouts.exercise.reportImage")
            ) {
                Icon(Icons.Outlined.Flag, stringResource(R.string.workout_image_report), tint = colors.accent)
            }
        }
    }
}

@Composable
private fun ExerciseImageReportDialog(item: ExerciseItem, visual: ExerciseVisual, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var note by rememberSaveable(item.id) { mutableStateOf("") }
    var openFailed by remember { mutableStateOf(false) }
    val copyLabel = stringResource(R.string.workout_image_report)
    val reference = when {
        visual.framePaths.isEmpty() -> "unavailable"
        visual.isAuthored -> visual.framePaths.first().substringAfterLast('/')
        else -> "catalog-photo"
    }
    val version = remember(context) { AndroidUpdateChecker.currentVersion(context) }
    FudGlassDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FlashSpacing.ItemGap)
        ) {
            Text(stringResource(R.string.workout_image_report), style = MaterialTheme.typography.titleLarge)
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            FudGlassTextField(
                value = note,
                onValueChange = { note = ExerciseImageReport.trimNote(it) },
                modifier = Modifier.testTag("workouts.exercise.imageReportNote"),
                placeholder = stringResource(R.string.workout_image_report_note),
                singleLine = false,
                minLines = 3,
                maxLines = 5
            )
            Text(
                stringResource(R.string.workout_image_report_review),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (openFailed) {
                Text(stringResource(R.string.workout_image_report_open_failed), color = MaterialTheme.colorScheme.error)
            }
            FudGlassDialogActions(
                primaryText = stringResource(R.string.about_report_issue),
                primaryEnabled = note.isNotBlank(),
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = onDismiss,
                onPrimary = {
                    val url = ExerciseImageReport.issueUrl(item.name, item.id, reference, version, note)
                    openFailed = runCatching {
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                    }.isFailure
                }
            )
            FudGlassTextButton(
                text = stringResource(R.string.about_report_issue_discord),
                color = MaterialTheme.colorScheme.onSurface,
                enabled = note.isNotBlank(),
                onClick = {
                    val report = ExerciseImageReport.text(item.name, item.id, reference, version, note)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, report))
                    openFailed = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FudAILinks.DISCORD)))
                    }.isFailure
                }
            )
            Text(
                stringResource(R.string.workout_image_report_discord_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricGrid(item: ExerciseItem, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(stringResource(R.string.label_level), item.level, Icons.Filled.BarChart, 1, 15.sp, Modifier.weight(1f))
            MetricCard(stringResource(R.string.label_category), item.category, Icons.Filled.Tag, 1, 15.sp, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard(stringResource(R.string.label_force), item.force, Icons.Filled.SwapHoriz, 1, 15.sp, Modifier.weight(1f))
            MetricCard(stringResource(R.string.label_mechanic), item.mechanic, Icons.Filled.Settings, 1, 15.sp, Modifier.weight(1f))
        }
        MetricCard(stringResource(R.string.label_primary), item.primaryMusclesTitle, Icons.Filled.GpsFixed, 2, 14.sp, Modifier.fillMaxWidth())
        MetricCard(stringResource(R.string.label_secondary), item.secondaryMusclesTitle, Icons.Filled.GpsFixed, 3, 13.sp, Modifier.fillMaxWidth())
        MetricCard(stringResource(R.string.label_equipment), item.equipment, Icons.Filled.FitnessCenter, 2, 14.sp, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: ImageVector, valueMaxLines: Int, valueSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
    val colors = workoutsColors()
    val dark = colors.isDark
    val labelColor = if (dark) colors.accent else colors.secondaryAccent
    val fill = if (dark) colors.background.copy(alpha = 0.55f) else colors.background.copy(alpha = 0.92f)
    val stroke = if (dark) colors.hairline.copy(alpha = 0.32f) else colors.hairline.copy(alpha = 0.45f)
    Column(
        modifier
            .shadow(6.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(fill)
            .border(0.6.dp, stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = labelColor, modifier = Modifier.size(11.dp))
            Text(title, color = labelColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Text(
            value,
            color = colors.charcoal,
            fontSize = valueSize,
            fontWeight = FontWeight.Bold,
            maxLines = valueMaxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InstructionSection(
    exerciseName: String,
    instructions: List<String>,
    modifier: Modifier = Modifier
) {
    val colors = workoutsColors()
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FlashSpacing.SectionGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.FormatListNumbered, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Text(stringResource(R.string.instructions), color = colors.charcoal, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.clip(CircleShape).background(colors.secondaryAccent.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text("${instructions.size}", color = colors.secondaryAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.panel.copy(alpha = 0.16f))
                .border(0.5.dp, colors.hairline.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                .clickable {
                    val query = URLEncoder.encode("$exerciseName exercise", StandardCharsets.UTF_8.toString())
                    val uri = Uri.parse("https://www.youtube.com/results?search_query=$query")
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(colors.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayCircle, null, tint = colors.accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.watch_on_youtube),
                    color = colors.charcoal,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.watch_on_youtube_hint),
                    color = colors.mutedText,
                    fontSize = 12.sp
                )
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = colors.mutedText, modifier = Modifier.size(16.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            instructions.forEachIndexed { index, instruction ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.panel.copy(alpha = 0.16f))
                        .border(0.5.dp, colors.hairline.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Box(
                        Modifier.size(27.dp).shadow(6.dp, CircleShape, clip = false).clip(CircleShape).background(colors.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", color = colors.onAccent, fontSize = 15.sp, fontWeight = FontWeight.Black)
                    }
                    Text(
                        instruction,
                        color = colors.charcoal.copy(alpha = 0.86f),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
