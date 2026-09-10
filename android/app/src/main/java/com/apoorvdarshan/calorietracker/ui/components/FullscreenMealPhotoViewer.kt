package com.apoorvdarshan.calorietracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.calorietracker.R
import kotlin.math.roundToInt

data class MealPhotoViewerState(
    val bitmaps: List<Bitmap>,
    val startIndex: Int = 0
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullscreenMealPhotoViewer(
    state: MealPhotoViewerState,
    onDismiss: () -> Unit
) {
    if (state.bitmaps.isEmpty()) return

    val startIndex = state.startIndex.coerceIn(0, state.bitmaps.lastIndex)
    val pagerState = rememberPagerState(initialPage = startIndex) { state.bitmaps.size }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .graphicsLayer {
                    alpha = 1f - (dragOffset / 300f).coerceIn(0f, 0.35f)
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount > 0f || dragOffset > 0f) {
                                dragOffset = (dragOffset + dragAmount).coerceAtLeast(0f)
                            }
                        },
                        onDragEnd = {
                            if (dragOffset > dismissThresholdPx) {
                                onDismiss()
                            } else {
                                dragOffset = 0f
                            }
                        },
                        onDragCancel = { dragOffset = 0f }
                    )
                }
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                androidx.compose.foundation.Image(
                    bitmap = state.bitmaps[page].asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_meal_photo, page + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = Color.White
                )
            }

            if (state.bitmaps.size > 1) {
                Text(
                    "${pagerState.currentPage + 1}/${state.bitmaps.size}",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}
