package com.apoorvdarshan.calorietracker.ui.progress

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.heartrate.HeartRateMeasurement
import com.apoorvdarshan.calorietracker.services.heartrate.HeartRateSignalProcessor
import com.apoorvdarshan.calorietracker.services.heartrate.PpgFrameSample
import com.apoorvdarshan.calorietracker.services.heartrate.PpgStage
import com.apoorvdarshan.calorietracker.services.heartrate.PpgUpdate
import com.apoorvdarshan.calorietracker.ui.components.FudGlassPrimaryButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class HeartRateCameraIssue {
    CAMERA,
    FLASH
}

/** Full-screen, local-only contact-PPG capture. Only the final BPM and quality can be saved. */
@Composable
internal fun HeartRateMeasurementDialog(
    onSave: (HeartRateMeasurement) -> Unit,
    onManualFallback: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = ContextCompat.getMainExecutor(context)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    var update by remember { mutableStateOf(PpgUpdate(PpgStage.FINDING_FINGER)) }
    var issue by remember { mutableStateOf<HeartRateCameraIssue?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    val processor = remember(retryKey) { HeartRateSignalProcessor() }
    val terminalHandled = remember(retryKey) { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner, retryKey) {
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        var boundAnalysis: ImageAnalysis? = null
        var absoluteCaptureTimeout: Runnable? = null
        val effectActive = AtomicBoolean(true)
        val lastAnalyzableFrameElapsedMs = AtomicLong(SystemClock.elapsedRealtime())

        fun releaseCapture() {
            absoluteCaptureTimeout?.let { mainHandler.removeCallbacks(it) }
            absoluteCaptureTimeout = null
            val analysis = boundAnalysis
            boundAnalysis = null
            runCatching { analysis?.clearAnalyzer() }
            val camera = boundCamera
            boundCamera = null
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { boundProvider?.unbindAll() }
            analyzerExecutor.shutdownNow()
        }

        val frameWatchdog = object : Runnable {
            override fun run() {
                if (!effectActive.get()) return
                if (terminalHandled.get()) return
                val stalledFor = SystemClock.elapsedRealtime() - lastAnalyzableFrameElapsedMs.get()
                if (stalledFor >= CAMERA_FRAME_WATCHDOG_MS &&
                    terminalHandled.compareAndSet(false, true)
                ) {
                    update = PpgUpdate(PpgStage.POOR_SIGNAL)
                    releaseCapture()
                    return
                }
                mainHandler.postDelayed(this, CAMERA_WATCHDOG_POLL_MS)
            }
        }
        mainHandler.postDelayed(frameWatchdog, CAMERA_WATCHDOG_POLL_MS)

        val listener = Runnable {
            if (!effectActive.get() || terminalHandled.get()) {
                analyzerExecutor.shutdownNow()
                return@Runnable
            }
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull()
            boundProvider = cameraProvider
            if (cameraProvider == null ||
                !runCatching { cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            ) {
                terminalHandled.set(true)
                issue = HeartRateCameraIssue.CAMERA
                releaseCapture()
                return@Runnable
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
            boundAnalysis = analysis
            analysis.setAnalyzer(analyzerExecutor) { image ->
                val sample = try {
                    image.toPpgSample()
                } finally {
                    image.close()
                }
                if (sample != null && effectActive.get()) {
                    lastAnalyzableFrameElapsedMs.set(SystemClock.elapsedRealtime())
                    val next = processor.ingest(sample)
                    val reachedTerminalState =
                        (next.stage == PpgStage.COMPLETE || next.stage == PpgStage.POOR_SIGNAL) &&
                        terminalHandled.compareAndSet(false, true)
                    mainExecutor.execute {
                        if (effectActive.get()) update = next
                        if (effectActive.get() && reachedTerminalState) {
                            releaseCapture()
                        }
                    }
                }
            }
            runCatching {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                boundCamera = camera
                if (!camera.cameraInfo.hasFlashUnit()) {
                    terminalHandled.set(true)
                    issue = HeartRateCameraIssue.FLASH
                    releaseCapture()
                } else {
                    // Keep auto-exposure; forced underexposure made side-lit fingertips too dark
                    // to pass contact detection on many phones.
                    val torchFuture = camera.cameraControl.enableTorch(true)
                    torchFuture.addListener(
                        {
                            val torchFailed = runCatching { torchFuture.get() }.isFailure
                            if (effectActive.get() && torchFailed) {
                                terminalHandled.set(true)
                                issue = HeartRateCameraIssue.FLASH
                                releaseCapture()
                            } else if (effectActive.get() && !terminalHandled.get()) {
                                val torchEnabledAt = SystemClock.elapsedRealtime()
                                lastAnalyzableFrameElapsedMs.set(torchEnabledAt)
                                val timeout = Runnable {
                                    if (effectActive.get() &&
                                        hasHeartRateCaptureTimedOut(
                                            torchEnabledAt,
                                            SystemClock.elapsedRealtime()
                                        ) &&
                                        terminalHandled.compareAndSet(false, true)
                                    ) {
                                        update = PpgUpdate(PpgStage.POOR_SIGNAL)
                                        releaseCapture()
                                    }
                                }
                                absoluteCaptureTimeout = timeout
                                mainHandler.postDelayed(timeout, ABSOLUTE_TORCH_CAPTURE_MS)
                            }
                        },
                        mainExecutor
                    )
                }
            }.onFailure {
                terminalHandled.set(true)
                issue = HeartRateCameraIssue.CAMERA
                releaseCapture()
            }
        }
        providerFuture.addListener(listener, mainExecutor)

        onDispose {
            effectActive.set(false)
            mainHandler.removeCallbacks(frameWatchdog)
            releaseCapture()
            boundProvider = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (issue == null) 0.48f else 0.82f))
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp).size(48.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.56f))
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = Color.White
                )
            }

            Column(
                modifier = Modifier.align(Alignment.Center).fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    modifier = Modifier.size(86.dp),
                    tint = AppColors.Calorie
                )
                Text(
                    stringResource(R.string.progress_heart_rate_camera_title),
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                when (val currentIssue = issue) {
                    HeartRateCameraIssue.CAMERA,
                    HeartRateCameraIssue.FLASH -> {
                        Text(
                            stringResource(
                                if (currentIssue == HeartRateCameraIssue.FLASH) {
                                    R.string.progress_heart_rate_flash_unavailable
                                } else {
                                    R.string.progress_heart_rate_camera_unavailable
                                }
                            ),
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        FudGlassPrimaryButton(
                            text = stringResource(R.string.progress_heart_rate_log_manual),
                            onClick = onManualFallback
                        )
                    }
                    null -> HeartRateMeasurementContent(
                        update = update,
                        onSave = { update.measurement?.let(onSave) },
                        onRetry = {
                            boundCamera?.cameraControl?.enableTorch(false)
                            retryKey += 1
                            issue = null
                            update = PpgUpdate(PpgStage.FINDING_FINGER)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartRateMeasurementContent(
    update: PpgUpdate,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    val guidance = when (update.stage) {
        PpgStage.FINDING_FINGER -> stringResource(R.string.progress_heart_rate_camera_guidance)
        PpgStage.MEASURING -> stringResource(
            if (update.refiningSignal) {
                R.string.progress_heart_rate_camera_refining
            } else {
                R.string.progress_heart_rate_camera_measuring
            }
        )
        PpgStage.COMPLETE -> stringResource(
            R.string.progress_heart_rate_camera_result,
            update.measurement?.bpm ?: 0
        )
        PpgStage.POOR_SIGNAL -> stringResource(R.string.progress_heart_rate_camera_poor_signal)
    }
    Text(
        guidance,
        color = Color.White,
        fontSize = 18.sp,
        lineHeight = 25.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
    if (update.stage == PpgStage.MEASURING) {
        LinearProgressIndicator(
            progress = { update.progress.toFloat() },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            color = AppColors.Calorie,
            trackColor = Color.White.copy(alpha = 0.18f)
        )
        Text(
            stringResource(R.string.progress_heart_rate_camera_hold_still),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
    update.measurement?.let { measurement ->
        Text(
            stringResource(
                R.string.progress_heart_rate_quality_format,
                (measurement.quality * 100).roundToInt()
            ),
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp
        )
    }
    Text(
        stringResource(R.string.progress_heart_rate_warmth_warning),
        color = Color.White.copy(alpha = 0.60f),
        fontSize = 12.sp,
        lineHeight = 17.sp,
        textAlign = TextAlign.Center
    )
    when (update.stage) {
        PpgStage.COMPLETE -> FudGlassPrimaryButton(
            text = stringResource(R.string.progress_heart_rate_save_reading),
            onClick = onSave
        )
        PpgStage.POOR_SIGNAL -> FudGlassPrimaryButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry
        )
        else -> Unit
    }
}

private const val CAMERA_FRAME_WATCHDOG_MS = 8_000L
private const val CAMERA_WATCHDOG_POLL_MS = 1_000L
internal const val ABSOLUTE_TORCH_CAPTURE_MS = 45_000L

internal fun hasHeartRateCaptureTimedOut(torchEnabledAtMs: Long, nowMs: Long): Boolean =
    nowMs >= torchEnabledAtMs && nowMs - torchEnabledAtMs >= ABSOLUTE_TORCH_CAPTURE_MS

/** Samples the center ROI from CameraX RGBA output (R, G, B, A byte order). */
private fun ImageProxy.toPpgSample(): PpgFrameSample? {
    val plane = planes.firstOrNull() ?: return null
    val means = extractRgbaMeans(
        buffer = plane.buffer,
        width = width,
        height = height,
        pixelStride = plane.pixelStride,
        rowStride = plane.rowStride
    ) ?: return null
    return PpgFrameSample(
        timestampNanos = imageInfo.timestamp,
        redMean = means.red,
        greenMean = means.green,
        blueMean = means.blue,
        redClippedFraction = means.redClippedFraction,
        redSpatialStdDev = means.redSpatialStdDev
    )
}

internal data class RgbMeans(
    val red: Double,
    val green: Double,
    val blue: Double,
    val redClippedFraction: Double,
    val redSpatialStdDev: Double
)

/** Pure stride-aware extractor so channel order and padded rows are unit-testable. */
internal fun extractRgbaMeans(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int
): RgbMeans? {
    val bytes = buffer.duplicate()
    if (pixelStride < 4 || rowStride <= 0) return null
    val left = width / 3
    val right = width * 2 / 3
    val top = height / 3
    val bottom = height * 2 / 3
    var red = 0L
    var redSquared = 0.0
    var redClipped = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in top until bottom step 4) {
        for (x in left until right step 4) {
            val offset = y * rowStride + x * pixelStride
            if (offset < 0 || offset + 3 >= bytes.limit()) continue
            val redValue = bytes.get(offset).toInt() and 0xFF
            red += redValue
            redSquared += redValue.toDouble() * redValue
            if (redValue >= 250) redClipped += 1
            green += bytes.get(offset + 1).toInt() and 0xFF
            blue += bytes.get(offset + 2).toInt() and 0xFF
            count += 1
        }
    }
    if (count == 0L) return null
    val redMean = red.toDouble() / count
    val redVariance = (redSquared / count - redMean * redMean).coerceAtLeast(0.0)
    return RgbMeans(
        red = redMean,
        green = green.toDouble() / count,
        blue = blue.toDouble() / count,
        redClippedFraction = redClipped.toDouble() / count,
        redSpatialStdDev = sqrt(redVariance)
    )
}
