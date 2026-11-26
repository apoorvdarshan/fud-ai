package com.apoorvdarshan.calorietracker.ui.components

import android.net.Uri
import android.widget.VideoView
import android.widget.MediaController
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.R

@Composable
internal fun MascotCafeIntro(onFinish: () -> Unit) {
    var playing by rememberSaveable { mutableStateOf(false) }
    if (playing) {
        Dialog(onDismissRequest = onFinish) { MascotCafePlayer(onFinish) }
    } else {
        Dialog(onDismissRequest = onFinish) {
            Surface(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().clickable(role = Role.Button) { playing = true }) {
                        Image(painterResource(R.drawable.mascot_cafe_preview), null,
                            Modifier.fillMaxWidth().aspectRatio(720f / 468f))
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(12.dp)
                                .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.8f))
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                            Text(stringResource(R.string.mascot_cafe_play), color = Color.White)
                        }
                    }
                    Text(stringResource(R.string.mascot_cafe_invite),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onFinish) { Text(stringResource(R.string.post_update_product_hunt_not_now)) }
                }
            }
        }
    }
}

@Composable
private fun MascotCafePlayer(onFinish: () -> Unit) {
    val finish by rememberUpdatedState(onFinish)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val video = remember(context) { VideoView(context) }
    var position by rememberSaveable { mutableIntStateOf(0) }
    DisposableEffect(video, lifecycle) {
        val controls = MediaController(context)
        video.setMediaController(controls)
        video.setOnPreparedListener {
            it.setVolume(1f, 1f)
            video.seekTo(position)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) video.start()
        }
        video.setOnCompletionListener { finish() }
        video.setOnErrorListener { _, _, _ -> finish(); true }
        video.setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.mascot_cafe}"))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    position = video.currentPosition
                    video.pause()
                }
                Lifecycle.Event.ON_RESUME -> video.start()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controls.hide()
            video.stopPlayback()
        }
    }
    Column(Modifier.fillMaxWidth().background(Color.Black)) {
        TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.workout_rest_skip), color = Color.White)
        }
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f), contentAlignment = Alignment.Center) {
            AndroidView(factory = { video }, modifier = Modifier.aspectRatio(2f / 3f))
        }
    }
}
