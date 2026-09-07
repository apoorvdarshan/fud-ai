package com.apoorvdarshan.calorietracker.ui.workouts

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.data.ExerciseVisualFormat
import kotlinx.coroutines.delay

/**
 * Cycling exercise visual — the Android analog of the iOS `AnimatedExerciseVisual`.
 * Legacy JPEGs keep their cropped, muted treatment; authored SVG/PNG sequences render
 * uncropped and in their original colors. Honors the system "remove animations" setting
 * and shows a meaningful representative frame instead of freezing at the standing pose.
 */
private val ExerciseImageFilter: ColorFilter = run {
    val saturation = ColorMatrix().apply { setToSaturation(0.19f) }
    val contrast = 1.10f
    val translate = (1f - contrast) * 127.5f + (-0.05f * 255f)
    val contrastBrightness = ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
    )
    contrastBrightness.timesAssign(saturation)
    ColorFilter.colorMatrix(contrastBrightness)
}

@Composable
fun AnimatedExerciseImage(
    visual: ExerciseVisual,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackLabel: String? = null
) {
    val colors = workoutsColors()
    val imagePaths = visual.framePaths

    if (imagePaths.isEmpty()) {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(colors.panel, colors.card, colors.accent.copy(alpha = 0.12f)))
            ),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.FitnessCenter, null, tint = colors.charcoal, modifier = Modifier.size(36.dp))
                if (!fallbackLabel.isNullOrBlank()) {
                    Text(
                        fallbackLabel.uppercase(),
                        color = colors.charcoal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                }
            }
        }
        return
    }

    val context = LocalContext.current
    val animationsEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val representativeIndex = visual.representativeFrameIndex.coerceIn(imagePaths.indices)
    var index by remember(imagePaths, visual.format, animationsEnabled) {
        mutableIntStateOf(if (animationsEnabled) 0 else representativeIndex)
    }

    LaunchedEffect(imagePaths, visual.format, animationsEnabled) {
        if (!animationsEnabled) {
            index = representativeIndex
            return@LaunchedEffect
        }
        if (imagePaths.size > 1 && animationsEnabled) {
            while (true) {
                delay(850)
                index = (index + 1) % imagePaths.size
            }
        }
    }

    Box(modifier.background(colors.background)) {
        imagePaths.forEachIndexed { i, path ->
            key(path, visual.format) {
                val assetUri = ExerciseRepository.imageAssetUri(path)
                val model = remember(path, visual.format) {
                    if (visual.format == ExerciseVisualFormat.SVG) {
                        ImageRequest.Builder(context)
                            .data(assetUri)
                            .decoderFactory(SvgDecoder.Factory())
                            .build()
                    } else {
                        assetUri
                    }
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = if (visual.format == ExerciseVisualFormat.JPEG) contentScale else ContentScale.Fit,
                    colorFilter = if (visual.format == ExerciseVisualFormat.JPEG) ExerciseImageFilter else null,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (i == index) 1f else 0f)
                )
            }
        }
    }
}
