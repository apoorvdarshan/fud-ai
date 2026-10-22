package com.apoorvdarshan.calorietracker.ui.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.CameraFrame
import com.apoorvdarshan.calorietracker.services.voice.VoiceAssistantController
import com.apoorvdarshan.calorietracker.services.voice.VoiceCameraHandle
import com.apoorvdarshan.calorietracker.services.voice.VoiceCameraMode
import com.apoorvdarshan.calorietracker.ui.components.CameraFocusController
import com.apoorvdarshan.calorietracker.ui.components.CameraFocusOverlay
import com.apoorvdarshan.calorietracker.ui.components.captureCameraSelector
import com.apoorvdarshan.calorietracker.ui.components.rememberCameraFocusController
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The camera inside the assistant. Food mode shows a live preview with a shutter; barcode
 * mode reads product barcodes with ML Kit. Either way the latest preview frame is kept as
 * a small JPEG (≤ 768 px on the long side) for providers that look at frames. Nothing is
 * recorded; a photo is only taken by the shutter or `capture_photo`.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun VoiceCameraPanel(
    controller: VoiceAssistantController,
    mode: VoiceCameraMode,
    capturing: Boolean,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.externalActivityPending = false
        permitted = granted
        if (!granted) controller.cameraFailed(permissionDenied = true)
    }
    LaunchedEffect(Unit) {
        if (!permitted) {
            controller.externalActivityPending = true
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val focus = rememberCameraFocusController()

    if (permitted) {
        DisposableEffect(lifecycleOwner, mode) {
            val binding = CameraBinding(context, controller, mode, focus)
            binding.bind(lifecycleOwner, previewView)
            onDispose { binding.release() }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
            .testTag("voiceAssistantCamera")
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Below the orb and close button that sit over the panel's top corners.
        CameraFocusOverlay(focus = focus, enabled = permitted && !capturing, hintTopPadding = 72.dp)

        IconButton(
            onClick = { controller.userClosedCamera() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.voice_close_camera), tint = Color.White)
        }

        if (mode == VoiceCameraMode.BARCODE) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.voice_barcode_hint),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val shutterLabel = stringResource(R.string.voice_take_photo)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f))
                    .border(4.dp, AppColors.Calorie, CircleShape)
                    .semantics { contentDescription = shutterLabel }
                    .testTag("voiceAssistantShutter")
                    .then(if (capturing) Modifier else Modifier.clickableNoRipple(onShutter)),
                contentAlignment = Alignment.Center
            ) {
                if (capturing) {
                    CircularProgressIndicator(color = AppColors.Calorie, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

/** Binds CameraX for one camera mode and serves the controller's frame and photo requests. */
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
private class CameraBinding(
    private val context: Context,
    private val controller: VoiceAssistantController,
    private val mode: VoiceCameraMode,
    private val focus: CameraFocusController
) : VoiceCameraHandle {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fudai-voice-camera") }
    private val latest = AtomicReference<CameraFrame?>(null)
    private val released = AtomicBoolean(false)
    private val scanned = AtomicBoolean(false)
    private val notifiedReady = AtomicBoolean(false)
    private var provider: ProcessCameraProvider? = null
    private var boundUseCases: Array<UseCase> = emptyArray()
    private var analysisUseCase: ImageAnalysis? = null
    private var boundCamera: Camera? = null
    @Volatile private var capture: ImageCapture? = null
    private var lastEncodedAt = 0L
    private val scanner = if (mode == VoiceCameraMode.BARCODE) {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    } else null

    fun bind(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released.get()) return@addListener
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null) {
                controller.cameraFailed(permissionDenied = false)
                return@addListener
            }
            provider = cameraProvider
            val previewBuilder = Preview.Builder()
            focus.instrument(previewBuilder)
            val preview = previewBuilder.build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            analysisUseCase = analysis
            // MAXIMIZE_QUALITY waits for autofocus before the shot; MINIMIZE_LATENCY did not.
            val photo = if (mode == VoiceCameraMode.FOOD) {
                ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(PHOTO_JPEG_QUALITY)
                    .build()
            } else null
            // A back camera, preferring one that can autofocus.
            val selector = cameraProvider.captureCameraSelector(allowFront = false)
            fun bindAll(vararg useCases: UseCase): Camera? = runCatching {
                cameraProvider.unbind(*boundUseCases)
                boundUseCases = arrayOf(*useCases)
                cameraProvider.bindToLifecycle(lifecycleOwner, checkNotNull(selector), *useCases)
            }.getOrNull()
            val withPhoto = photo?.let { bindAll(preview, it, analysis) }
            // Some devices can't run three use cases at once; photos then come from the analysis stream.
            val camera = withPhoto ?: bindAll(preview, analysis)
            if (camera == null) {
                controller.cameraFailed(permissionDenied = false)
                return@addListener
            }
            capture = if (withPhoto != null) photo else null
            boundCamera = camera
            focus.attach(camera, preview, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        boundCamera?.let { focus.detach(only = it) }
        controller.cameraUnbound(this)
        runCatching { analysisUseCase?.clearAnalyzer() }
        runCatching { provider?.unbind(*boundUseCases) }
        runCatching { scanner?.close() }
        executor.shutdown()
    }

    override fun latestFrame(): CameraFrame? = latest.get()

    override suspend fun takePhoto(): ByteArray? = withContext(Dispatchers.Main.immediate) {
        try {
            // Lets autofocus settle, or scans the middle when it gave up (close-ups).
            val refocused = focus.prepareForCapture()
            val photo = capture
            if (photo == null) {
                // The shutter uses analysis frames here: wait for one the camera took after focusing.
                val focusedAt = if (refocused) focus.latestFrameTimestampNanos() else null
                if (focusedAt != null) awaitPhotoFrameAfter(focusedAt)
                return@withContext fullPhoto?.jpeg
            }
            val dir = File(context.cacheDir, "voice-camera").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            suspendCancellableCoroutine { continuation ->
                photo.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            val bytes = runCatching { file.readBytes() }.getOrNull()
                            runCatching { file.delete() }
                            if (continuation.isActive) continuation.resume(bytes?.takeIf { it.isNotEmpty() })
                        }

                        override fun onError(exception: ImageCaptureException) {
                            runCatching { file.delete() }
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            }
        } finally {
            focus.captureFinished()
        }
    }

    /**
     * Waits for an analysis frame exposed after [sensorTimestampNanos] (the camera's own clock,
     * shared by preview results and analysis frames). If none is encoded in time, e.g. the
     * analyzer stalls, the latest frame is used, as before focusing was added: analyzing a
     * slightly soft photo beats reporting a failed one and making the user ask again.
     */
    private suspend fun awaitPhotoFrameAfter(sensorTimestampNanos: Long) {
        photoWantedAfterNanos = sensorTimestampNanos
        try {
            withTimeoutOrNull(FRESH_FRAME_TIMEOUT_MS) {
                while ((fullPhoto?.sensorTimestampNanos ?: Long.MIN_VALUE) <= sensorTimestampNanos && !released.get()) {
                    delay(FRESH_FRAME_POLL_MS)
                }
            }
        } finally {
            photoWantedAfterNanos = Long.MAX_VALUE
        }
    }

    /** The largest analysis frame, for the shutter without ImageCapture, and when it was exposed. */
    private class TimedPhoto(val jpeg: ByteArray, val sensorTimestampNanos: Long)

    @Volatile private var fullPhoto: TimedPhoto? = null

    /** Set while the shutter waits: the first frame exposed after it is encoded at once. */
    @Volatile private var photoWantedAfterNanos = Long.MAX_VALUE

    private fun analyze(image: ImageProxy) {
        try {
            if (released.get()) return
            val now = SystemClock.elapsedRealtime()
            val wanted = image.imageInfo.timestamp > photoWantedAfterNanos
            if (wanted || now - lastEncodedAt >= FRAME_ENCODE_INTERVAL_MS) {
                lastEncodedAt = now
                encodeFrames(image)
            }
            val barcodeScanner = scanner
            if (barcodeScanner != null && !scanned.get()) {
                val media = image.image ?: return
                val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                val result = runCatching { Tasks.await(barcodeScanner.process(input)) }.getOrNull()
                val code = result?.firstNotNullOfOrNull { it.rawValue?.trim()?.takeIf(String::isNotEmpty) }
                if (code != null && scanned.compareAndSet(false, true)) {
                    ContextCompat.getMainExecutor(context).execute {
                        if (!released.get()) controller.barcodeDetected(this, code)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun encodeFrames(image: ImageProxy) {
        val bitmap = runCatching { image.toBitmap() }.getOrNull() ?: return
        val upright = rotated(bitmap, image.imageInfo.rotationDegrees)
        latest.set(CameraFrame(jpeg(upright, MAX_FRAME_SIDE, FRAME_QUALITY), SystemClock.elapsedRealtime()))
        // Without ImageCapture, the shutter uses the largest analysis frame.
        if (capture == null && mode == VoiceCameraMode.FOOD) {
            fullPhoto = TimedPhoto(jpeg(upright, MAX_PHOTO_SIDE, PHOTO_QUALITY), image.imageInfo.timestamp)
        }
        if (notifiedReady.compareAndSet(false, true)) {
            ContextCompat.getMainExecutor(context).execute {
                if (!released.get()) controller.cameraReady(this)
            }
        }
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun jpeg(bitmap: Bitmap, maxSide: Int, quality: Int): ByteArray {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > maxSide) {
            val factor = maxSide.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt(), (bitmap.height * factor).roundToInt(), true)
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bitmap) scaled.recycle()
        return out.toByteArray()
    }

    companion object {
        private const val FRAME_ENCODE_INTERVAL_MS = 500L
        /** The contract caps frames sent to the model at 768 px on the long side. */
        const val MAX_FRAME_SIDE = 768
        private const val FRAME_QUALITY = 70
        private const val MAX_PHOTO_SIDE = 1_920
        private const val PHOTO_QUALITY = 90
        /** The quality the earlier low-latency capture mode used; MAXIMIZE_QUALITY alone writes 100. */
        private const val PHOTO_JPEG_QUALITY = 95
        /** The first frame after focusing is encoded straight away, well within this. */
        private const val FRESH_FRAME_TIMEOUT_MS = 1_500L
        private const val FRESH_FRAME_POLL_MS = 40L
    }
}
