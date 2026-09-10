package com.apoorvdarshan.calorietracker.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.calorietracker.R
import kotlin.math.abs

/**
 * Full-screen meal photo viewer. Supports horizontal paging for multi-photo entries,
 * a close button, and vertical drag-to-dismiss.
 */
@Composable
fun FullScreenImageViewer(
    bitmaps: List<Bitmap>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit
) {
    if (bitmaps.isEmpty()) return

    val startIndex = initialIndex.coerceIn(0, bitmaps.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = startIndex,
        pageCount = { bitmaps.size }
    )
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThresholdPx = 180f

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
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        },
                        onDragEnd = {
                            if (abs(dragOffset) > dismissThresholdPx) {
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
                    bitmap = bitmaps[page].asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_meal_photo, page + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
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
        }
    }
}
