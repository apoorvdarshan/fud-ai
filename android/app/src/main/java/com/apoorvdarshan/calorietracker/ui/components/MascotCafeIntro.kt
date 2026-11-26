package com.apoorvdarshan.calorietracker.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
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
    val finish by rememberUpdatedState(onFinish)
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val video = remember(context) { VideoView(context) }
    var position by rememberSaveable { mutableIntStateOf(0) }
    BackHandler { finish() }
    DisposableEffect(video, lifecycle) {
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
            video.stopPlayback()
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.workout_rest_skip), color = Color.White)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(factory = { video }, modifier = Modifier.aspectRatio(2f / 3f))
        }
    }
}
