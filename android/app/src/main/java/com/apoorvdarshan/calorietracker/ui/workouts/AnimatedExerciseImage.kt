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
 * arrive through [WorkoutFrameStore] (cache → bundled asset → optional debug
 * download); until a frame is available the card shows the workout background,
 * never a broken image.
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
    // Authored frames may be unavailable (frame missing from the bundled corpus, or a
    // debug build packaged with -PworkoutVectors=sample|none). Fall back to the icon
    // placeholder instead of an empty card.
    var frameUnavailable by remember(visual) { mutableStateOf(false) }
    // Coil never retries a failed request by itself, and a non-animating card keeps the
    // same `index` forever, so a transient outage would otherwise leave the thumbnail
    // blank until the composable is recreated. Bump the attempt counter with backoff
    // while unavailable; the request carries it as a parameter so AsyncImage sees a
    // new model without changing the cache keys.
    var retryAttempt by remember(visual) { mutableIntStateOf(0) }
    LaunchedEffect(frameUnavailable, retryAttempt, visual) {
        if (!frameUnavailable || !visual.isAuthored) return@LaunchedEffect
        delay(frameRetryDelayMillis(retryAttempt))
        retryAttempt++
    }
    Box(modifier.background(colors.background)) {
        if (frameUnavailable) {
            ExerciseImagePlaceholder(Modifier.fillMaxSize(), colors, fallbackLabel)
        }
        AsyncImage(
            model = remember(visual, index, retryAttempt) {
                exerciseImageRequest(context, imageStore, visual, index, retryAttempt)
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

/**
 * Backoff between UI-driven retries of an unavailable frame. [WorkoutFrameStore] already
 * short-circuits repeat downloads for 60s after a failure, so early attempts mostly pick
 * up frames that appeared in the cache meanwhile; the cap matches its retry window.
 */
private fun frameRetryDelayMillis(attempt: Int): Long =
    (FRAME_RETRY_BASE_MS shl attempt.coerceIn(0, 2)).coerceAtMost(FRAME_RETRY_MAX_MS)

private const val FRAME_RETRY_BASE_MS = 15_000L
private const val FRAME_RETRY_MAX_MS = 60_000L
private const val FRAME_RETRY_ATTEMPT_PARAMETER = "workoutFrameRetryAttempt"

private fun exerciseImageRequest(
    context: Context,
    imageStore: FoodImageStore,
    visual: ExerciseVisual,
    index: Int,
    retryAttempt: Int = 0
): ImageRequest {
    val path = visual.framePaths[index]
    if (visual.isAuthored) {
        // Authored frames are bundled assets; WorkoutFrameStore's fetcher serves them from
        // the on-device cache, the bundle, or (debug builds only) a configured download URL.
        val ref = WorkoutFrameRef.from(path, visual.digestAt(index), visual.format)
        if (ref != null) {
            val cacheKey = "${ref.name}:${ref.digest ?: "nodigest"}"
            return ImageRequest.Builder(context)
                .data(ref)
                .memoryCacheKey(cacheKey)
                .diskCacheKey(cacheKey)
                // Distinguishes retries for ImageRequest.equals only; excluded from cache keys.
                .setParameter(FRAME_RETRY_ATTEMPT_PARAMETER, retryAttempt, memoryCacheKey = null)
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
