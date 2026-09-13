package com.apoorvdarshan.calorietracker.ui.workouts

import android.content.Context
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import coil.imageLoader
import coil.request.ImageRequest
import com.apoorvdarshan.calorietracker.data.ExerciseRepository
import com.apoorvdarshan.calorietracker.data.ExerciseVisual
import com.apoorvdarshan.calorietracker.data.ExerciseVisualFormat
import com.apoorvdarshan.calorietracker.models.UserExercise
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import com.apoorvdarshan.calorietracker.services.WorkoutFrameRef
import com.apoorvdarshan.calorietracker.services.WorkoutFrameStore
import kotlinx.coroutines.delay

/**
 * Cycling exercise visual — Android analog of iOS `AnimatedExerciseVisual`.
 * Legacy JPEGs stay cropped/muted; authored SVG/PNG sequences render uncropped
 * in original colors. Honors system "remove animations" and freezes on the
 * representative frame. Composes only the visible frame (optional next-frame
 * Coil prefetch) so list rows do not decode every PNG up front. Authored frames
 * arrive through [WorkoutFrameStore] (cache → debug sample → CDN); until a frame
 * is available the card shows the workout background, never a broken image.
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
    fallbackLabel: String? = null,
    animatesFrames: Boolean = true
) {
    val colors = workoutsColors()
    val imagePaths = visual.framePaths

    if (imagePaths.isEmpty()) {
        ExerciseImagePlaceholder(modifier, colors, fallbackLabel)
        return
    }

    val context = LocalContext.current
    val imageStore = remember(context) { FoodImageStore(context) }
    val frameStore = remember(context) { WorkoutFrameStore.get(context) }
    val imageLoader = if (visual.isAuthored) frameStore.imageLoader else context.imageLoader
    val animationsEnabled = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val representativeIndex = visual.representativeFrameIndex.coerceIn(imagePaths.indices)
    val shouldAnimate = animatesFrames && animationsEnabled
    var index by remember(imagePaths, visual.format, shouldAnimate) {
        mutableIntStateOf(if (shouldAnimate) 0 else representativeIndex)
    }

    LaunchedEffect(imagePaths, visual.format, shouldAnimate) {
        if (!shouldAnimate) {
            index = representativeIndex
            return@LaunchedEffect
        }
        if (imagePaths.size <= 1) return@LaunchedEffect
        while (true) {
            delay(850)
            index = (index + 1) % imagePaths.size
        }
    }

    val prefetchIndex = if (shouldAnimate && imagePaths.size > 1) (index + 1) % imagePaths.size else null

    LaunchedEffect(prefetchIndex, visual) {
        val next = prefetchIndex ?: return@LaunchedEffect
        imageLoader.enqueue(exerciseImageRequest(context, imageStore, visual, next))
    }

    val isJpeg = visual.format == ExerciseVisualFormat.JPEG
    // Authored frames may be unavailable (offline before first download, CDN not
    // reachable). Fall back to the icon placeholder instead of an empty card.
    var frameUnavailable by remember(visual) { mutableStateOf(false) }
    Box(modifier.background(colors.background)) {
        if (frameUnavailable) {
            ExerciseImagePlaceholder(Modifier.fillMaxSize(), colors, fallbackLabel)
        }
        AsyncImage(
            model = remember(visual, index) {
                exerciseImageRequest(context, imageStore, visual, index)
            },
            imageLoader = imageLoader,
            contentDescription = null,
            onSuccess = { frameUnavailable = false },
            onError = { frameUnavailable = true },
            contentScale = if (isJpeg) contentScale else ContentScale.Fit,
            colorFilter = if (isJpeg) ExerciseImageFilter else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun ExerciseImagePlaceholder(
    modifier: Modifier,
    colors: WorkoutsColors,
    fallbackLabel: String?
) {
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
}

private fun exerciseImageRequest(
    context: Context,
    imageStore: FoodImageStore,
    visual: ExerciseVisual,
    index: Int
): ImageRequest {
    val path = visual.framePaths[index]
    if (visual.isAuthored) {
        // Authored frames are not bundled in release builds; WorkoutFrameStore's fetcher
        // serves them from the on-device cache, the debug sample pack, or the CDN.
        val ref = WorkoutFrameRef.from(path, visual.digestAt(index), visual.format)
        if (ref != null) {
            val cacheKey = "${ref.name}:${ref.digest ?: "nodigest"}"
            return ImageRequest.Builder(context)
                .data(ref)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                .build()
        }
    }
    val localFile = path
        .takeIf { UserExercise.isUserPhotoFilename(it) }
        ?.let { imageStore.file(it).takeIf { file -> file.isFile } }
    val builder = ImageRequest.Builder(context)
        .data(localFile ?: ExerciseRepository.imageAssetUri(path))
        .memoryCacheKey(path)
        .diskCacheKey(path)
    if (visual.format == ExerciseVisualFormat.SVG) {
        builder.decoderFactory(SvgDecoder.Factory())
    }
    return builder.build()
}
