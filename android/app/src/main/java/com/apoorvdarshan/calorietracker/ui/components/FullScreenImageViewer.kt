package com.apoorvdarshan.calorietracker.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.apoorvdarshan.calorietracker.R
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 4f

/**
 * Full-screen meal photo viewer. Supports pinch/double-tap zoom, pan while zoomed,
 * horizontal paging when zoomed out, save to gallery, close, and vertical drag-to-dismiss.
 */
@Composable
fun FullScreenImageViewer(
    bitmaps: List<Bitmap>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    if (bitmaps.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val startIndex = initialIndex.coerceIn(0, bitmaps.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { bitmaps.size }
    )
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var zoomedPage by remember { mutableIntStateOf(-1) }
    var isSaving by remember { mutableStateOf(false) }
    val dismissThresholdPx = 180f
    val isCurrentPageZoomed = zoomedPage == pagerState.currentPage

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            scope.launch { saveCurrentPhoto(context, bitmaps, pagerState.currentPage, isSaving) { isSaving = it } }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.photo_save_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun requestSaveCurrentPhoto() {
        if (isSaving) return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        scope.launch {
            saveCurrentPhoto(context, bitmaps, pagerState.currentPage, isSaving) { isSaving = it }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = (1f - (abs(dragOffset) / 500f)).coerceIn(0.35f, 1f)))
                .graphicsLayer { translationY = dragOffset }
                .pointerInput(isCurrentPageZoomed) {
                    if (!isCurrentPageZoomed) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            var tracking = false
                            val touchSlop = viewConfiguration.touchSlop

                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } > 1) {
                                    if (tracking) dragOffset = 0f
                                    return@awaitEachGesture
                                }

                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null || !change.pressed) {
                                    if (tracking) {
                                        if (abs(dragOffset) > dismissThresholdPx) {
                                            onDismiss()
                                        } else {
                                            dragOffset = 0f
                                        }
                                    }
                                    return@awaitEachGesture
                                }

                                val dragAmount = change.positionChange().y
                                if (!tracking) {
                                    if (abs(dragAmount) < touchSlop) continue
                                    tracking = true
                                }
                                change.consume()
                                dragOffset += dragAmount
                            }
                        }
                    }
                }
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !isCurrentPageZoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomableMealPhotoPage(
                    bitmap = bitmaps[page],
                    pageIndex = page,
                    isActive = pagerState.currentPage == page,
                    isZoomed = zoomedPage == page,
                    onZoomChanged = { zoomed ->
                        zoomedPage = if (zoomed) page else {
                            if (zoomedPage == page) -1 else zoomedPage
                        }
                    }
                )
            }

            if (bitmaps.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${bitmaps.size}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(start = 20.dp, top = 12.dp)
                        .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 12.dp, top = 4.dp)
                    .size(44.dp)
                    .background(Color.White.copy(alpha = 0.22f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = Color.White
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                    .clickable(enabled = !isSaving) { requestSaveCurrentPhoto() }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_save),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ZoomableMealPhotoPage(
    bitmap: Bitmap,
    pageIndex: Int,
    isActive: Boolean,
    isZoomed: Boolean,
    onZoomChanged: (Boolean) -> Unit
) {
    var scale by remember(pageIndex) { mutableFloatStateOf(1f) }
    var offsetX by remember(pageIndex) { mutableFloatStateOf(0f) }
    var offsetY by remember(pageIndex) { mutableFloatStateOf(0f) }
    var containerSize by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    val isPageZoomed = scale > 1.01f

    LaunchedEffect(isActive) {
        if (!isActive) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            onZoomChanged(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerSize = Offset(size.width.toFloat(), size.height.toFloat())
            }
            .pointerInput(pageIndex, isActive, isPageZoomed) {
                if (!isActive) return@pointerInput
                if (isPageZoomed) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val updatedScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        scale = updatedScale
                        if (updatedScale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                        onZoomChanged(updatedScale > 1.01f)
                    }
                } else {
                    detectPinchToZoomGestures { pan, zoom ->
                        val updatedScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                        scale = updatedScale
                        if (updatedScale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                        onZoomChanged(updatedScale > 1.01f)
                    }
                }
            }
            .pointerInput(pageIndex, isActive) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (!isActive) return@detectTapGestures
                        if (scale > 1.01f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChanged(false)
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                            offsetX = 0f
                            offsetY = 0f
                            centerPanForZoom(tapOffset, containerSize, scale)?.let { (x, y) ->
                                offsetX = x
                                offsetY = y
                            }
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_meal_photo, pageIndex + 1),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

private fun centerPanForZoom(
    tapOffset: Offset,
    containerSize: Offset,
    scale: Float
): Pair<Float, Float>? {
    if (scale <= 1.01f || containerSize == Offset.Zero) return null
    val centerX = containerSize.x / 2f
    val centerY = containerSize.y / 2f
    return ((centerX - tapOffset.x) * (scale - 1f)) to ((centerY - tapOffset.y) * (scale - 1f))
}

private suspend fun PointerInputScope.detectPinchToZoomGestures(
    onGesture: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.isEmpty() -> return@awaitEachGesture
                pressed.size >= 2 -> break
            }
        }

        var previousCentroid = Offset.Zero
        var previousSpan = 0f
        var initialized = false

        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size < 2) break

            val centroid = pressed
                .map { it.position }
                .reduce { accumulated, position -> accumulated + position } / pressed.size.toFloat()
            val span = computePointerSpan(pressed)

            if (!initialized) {
                previousCentroid = centroid
                previousSpan = span
                initialized = true
            } else {
                val zoom = if (previousSpan > 0f) span / previousSpan else 1f
                val pan = centroid - previousCentroid
                pressed.forEach { it.consume() }
                onGesture(pan, zoom)
                previousCentroid = centroid
                previousSpan = span
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun computePointerSpan(changes: List<PointerInputChange>): Float {
    if (changes.size < 2) return 0f
    var maxDistance = 0f
    for (first in changes.indices) {
        for (second in first + 1 until changes.size) {
            maxDistance = max(
                maxDistance,
                (changes[first].position - changes[second].position).getDistance()
            )
        }
    }
    return maxDistance
}

private suspend fun saveCurrentPhoto(
    context: android.content.Context,
    bitmaps: List<Bitmap>,
    page: Int,
    isSaving: Boolean,
    setSaving: (Boolean) -> Unit
) {
    if (isSaving || page !in bitmaps.indices) return
    setSaving(true)
    val result = try {
        withContext(Dispatchers.IO) {
            runCatching { saveMealPhotoToGallery(context, bitmaps[page]) }
                .getOrElse { MealPhotoSaveResult.Failed }
        }
    } finally {
        setSaving(false)
    }

    val messageRes = when (result) {
        MealPhotoSaveResult.Saved -> R.string.photo_saved_to_gallery
        MealPhotoSaveResult.PermissionDenied -> R.string.photo_save_permission_denied
        MealPhotoSaveResult.Failed -> R.string.photo_save_failed
    }
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}
