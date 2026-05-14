package com.apoorvdarshan.calorietracker.ui.coach

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.delay

/**
 * Verbatim port of struct ChatView in
 * ios/calorietracker/Views/ChatView.swift.
 *
 * Layout (top to bottom):
 *   - TopAppBar with "Coach" title + reset icon (disabled when empty)
 *   - empty state OR message list (weight 1f)
 *   - horizontal scrolling promptChips (always visible)
 *   - capsule input bar with gradient send button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(container: AppContainer) {
    val vm: CoachViewModel = viewModel(factory = CoachViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    var showResetConfirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) attachedImageBytes = resizedJpeg(bytes, maxDimension = 1800, quality = 86) ?: bytes
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        if (saved && file != null && file.exists()) {
            val bytes = file.readBytes()
            if (bytes.isNotEmpty()) attachedImageBytes = resizedJpeg(bytes, maxDimension = 1800, quality = 86) ?: bytes
        }
    }

    fun launchCamera() {
        val dir = File(ctx.cacheDir, "capture").apply { mkdirs() }
        val file = File(dir, "coach-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        pendingCaptureFile = file
        cameraLauncher.launch(uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun sendCurrentDraft() {
        val image = attachedImageBytes
        val trimmed = input.trim()
        if (trimmed.isEmpty() && image == null) return
        if (ui.sending) return
        val imageForAi = image?.let { resizedJpeg(it, maxDimension = 1600, quality = 78) ?: it }
        val thumbnail = image?.let { resizedJpeg(it, maxDimension = 700, quality = 68) ?: it }
        hideKeyboard()
        input = ""
        attachedImageBytes = null
        vm.send(trimmed, imageBytes = imageForAi, thumbnailBytes = thumbnail)
    }

    LaunchedEffect(ui.messages.size, ui.sending) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // iOS Coach: centered "Coach" title, with a small circular dark
            // chip on the right wrapping a counterclockwise arrow reset icon.
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.coach_title), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    val canReset = ui.messages.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f))
                            .clickable(enabled = canReset) { showResetConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Replay,
                            contentDescription = stringResource(R.string.coach_reset_chat_a11y),
                            tint = if (canReset)
                                MaterialTheme.colorScheme.onBackground
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Top region — empty state OR message list
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { hideKeyboard() })
                    }
            ) {
                if (ui.messages.isEmpty()) {
                    EmptyState(modifier = Modifier.fillMaxSize())
                } else {
                    val resolvedError = ui.error ?: ui.errorRes?.let { stringResource(it) }
                    MessageList(
                        messages = ui.messages,
                        sending = ui.sending,
                        error = resolvedError,
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // promptChips — horizontal scrolling, ALWAYS visible (matches iOS)
            val resolvedChips = ui.suggestions.map { stringResource(it) }
            PromptChipRow(
                chips = resolvedChips,
                enabled = !ui.sending,
                onTap = { chip ->
                    hideKeyboard()
                    input = ""
                    attachedImageBytes = null
                    vm.send(chip)
                }
            )

            // input bar — capsule with gradient send button
            InputBar(
                value = input,
                onValueChange = { input = it },
                attachedImageBytes = attachedImageBytes,
                sending = ui.sending,
                onPickImage = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onCaptureImage = { openCamera() },
                onRemoveImage = { attachedImageBytes = null },
                onSend = { sendCurrentDraft() }
            )
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.coach_reset_dialog_title)) },
            text = { Text(stringResource(R.string.coach_reset_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.resetConversation()
                    showResetConfirm = false
                }) { Text(stringResource(R.string.coach_reset_confirm), color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

}

/**
 * Verbatim port of `emptyState` in ChatView.swift.
 * 108dp glassy disc with bubble.left.and.bubble.right.fill (44sp) icon,
 * "Ask your Coach" title (rounded title2 semibold), subtitle.
 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(108.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = AppColors.Calorie.copy(alpha = 0.18f),
                    spotColor = AppColors.Calorie.copy(alpha = 0.18f)
                )
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .border(
                    0.8.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f))
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Forum,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = AppColors.Calorie
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.coach_empty_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.coach_empty_subtitle),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    sending: Boolean,
    error: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }

        if (sending) {
            item("typing") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .border(
                                0.5.dp,
                                Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(18.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) { TypingIndicator() }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (error != null) {
            item("error") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFEBEE).copy(alpha = 0.6f))
                        .border(0.5.dp, Color(0xFFD32F2F).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(error, fontSize = 12.sp, color = Color(0xFFD32F2F))
                }
            }
        }
    }
}

/**
 * 3-dot animated typing indicator. Cycles a "phase" 0 -> 1 -> 2 every 350ms;
 * the dot whose index == phase scales to 1.15 and goes opaque.
 * Verbatim port of struct TypingIndicator in ChatView.swift.
 */
@Composable
private fun TypingIndicator() {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(350)
            phase = (phase + 1) % 3
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val active = i == phase
            val scale by animateFloatAsState(
                targetValue = if (active) 1.15f else 1.0f,
                animationSpec = tween(durationMillis = 350),
                label = "typingScale"
            )
            val alpha by animateFloatAsState(
                targetValue = if (active) 1.0f else 0.3f,
                animationSpec = tween(durationMillis = 350),
                label = "typingAlpha"
            )
            // iOS uses `.opacity(phase == i ? 1 : 0.3)` which dims the *whole* dot.
            // Use Modifier.alpha so the gradient fades uniformly instead of getting
            // a white overlay (the previous attempt actually brightened inactive dots).
            Box(
                Modifier
                    .size(7.dp)
                    .scale(scale)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(AppColors.CalorieGradient)
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            AssistantBadge()
            Spacer(Modifier.width(8.dp))
            Bubble(content = msg.content, isUser = false)
            Spacer(Modifier.width(48.dp))
        } else {
            Spacer(Modifier.width(48.dp))
            Bubble(content = msg.content, isUser = true, attachmentImageBase64 = msg.attachmentImageBase64)
        }
    }
}

/** 26dp glassy disc with gradient sparkles icon. Verbatim port of `assistantBadge`. */
@Composable
private fun AssistantBadge() {
    Box(
        Modifier
            .padding(top = 8.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(11.dp),
            tint = AppColors.Calorie
        )
    }
}

/**
 * Verbatim port of `bubble`.
 *   .font(.system(.body, design: .rounded))            -> 17sp
 *   .padding(.horizontal, 16).padding(.vertical, 11)    -> same
 *   user background = LinearGradient(calorieGradient)
 *   assistant background = ultraThinMaterial + Calorie 0.035 tint
 *   stroke = LinearGradient white 0.45->0.05 user / 0.22->0.04 assistant
 *   user has top white 0.35->0 highlight (fakes .blendMode(.plusLighter))
 *   shadow user: Calorie 0.28, radius 10, y 6
 *   shadow asst: Black 0.12, radius 6, y 3
 */
@Composable
private fun Bubble(content: String, isUser: Boolean, attachmentImageBase64: String? = null) {
    val shape = RoundedCornerShape(20.dp)
    val borderBrush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isUser) 0.45f else 0.22f),
            Color.White.copy(alpha = if (isUser) 0.05f else 0.04f)
        )
    )
    val shadowElevation = if (isUser) 10.dp else 6.dp
    val shadowColor = if (isUser) AppColors.Calorie.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.12f)

    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .shadow(
                elevation = shadowElevation,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .clip(shape)
            .then(
                if (isUser) {
                    Modifier.background(AppColors.CalorieGradient)
                } else {
                    Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                        .background(AppColors.Calorie.copy(alpha = 0.035f))
                }
            )
            .border(0.7.dp, borderBrush, shape)
    ) {
        if (isUser) {
            // Top white highlight — fakes SwiftUI .blendMode(.plusLighter).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            attachmentImageBase64?.let { encoded ->
                val bitmap = remember(encoded) {
                    runCatching {
                        val bytes = Base64.getDecoder().decode(encoded)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text(
                content,
                fontSize = 17.sp,
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface,
                lineHeight = 22.sp,
                style = TextStyle(fontWeight = FontWeight.Normal)
            )
        }
    }
}

/**
 * Horizontal scrolling chips. Verbatim port of `promptChips`.
 *   ScrollView(.horizontal) HStack spacing 8
 *     Capsule (ultraThinMaterial + Calorie 0.10 fill + Calorie 0.35->0.10 stroke)
 *     padding 14h × 9v, footnote rounded medium, calorie text
 */
@Composable
private fun PromptChipRow(chips: List<String>, enabled: Boolean, onTap: (String) -> Unit) {
    if (chips.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(chips) { chip -> PromptChip(chip, enabled, onTap) }
    }
}

@Composable
private fun PromptChip(text: String, enabled: Boolean, onTap: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val strokeBrush = Brush.linearGradient(
        listOf(
            AppColors.Calorie.copy(alpha = 0.35f),
            AppColors.Calorie.copy(alpha = 0.10f)
        )
    )
    Box(
        Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .background(AppColors.Calorie.copy(alpha = 0.10f))
            .border(0.6.dp, strokeBrush, shape)
            .clickable(enabled = enabled) { onTap(text) }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = AppColors.Calorie
        )
    }
}

/**
 * Capsule input bar. Verbatim port of `inputBar`.
 *   capsule containing TextField + 34dp gradient send button
 *   ultraThinMaterial fill + glassy stroke + drop shadow
 *   send: arrow.up icon, 16sp bold, white-on-gradient when canSend, gray otherwise
 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    attachedImageBytes: ByteArray?,
    sending: Boolean,
    onPickImage: () -> Unit,
    onCaptureImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit
) {
    val canSend = !sending && (value.trim().isNotEmpty() || attachedImageBytes != null)
    val capsule = RoundedCornerShape(28.dp)

    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 10.dp)
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                shape = capsule,
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f)
            )
            .clip(capsule)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .border(
                0.8.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.05f))
                ),
                capsule
            )
            .padding(start = 4.dp, end = 5.dp, top = 4.dp, bottom = 4.dp),
    ) {
        attachedImageBytes?.let { bytes ->
            val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)
                        .size(width = 88.dp, height = 70.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove image", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onPickImage,
                enabled = !sending,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "Add image",
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = onCaptureImage,
                enabled = !sending,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    Icons.Filled.CameraAlt,
                    contentDescription = "Open camera",
                    tint = AppColors.Calorie,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 8.dp)) {
                if (value.isEmpty()) {
                    Text(
                        stringResource(R.string.coach_input_placeholder),
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(AppColors.Calorie),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SendButton(canSend = canSend, onClick = onSend)
        }
    }
}

@Composable
private fun SendButton(canSend: Boolean, onClick: () -> Unit) {
    val size: Dp = 34.dp
    val shape = CircleShape
    Box(
        Modifier
            .size(size)
            .then(
                if (canSend) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = AppColors.Calorie.copy(alpha = 0.35f),
                        spotColor = AppColors.Calorie.copy(alpha = 0.35f)
                    )
                } else Modifier
            )
            .clip(shape)
            .then(
                if (canSend) Modifier.background(AppColors.CalorieGradient)
                else Modifier.background(Color.Gray.copy(alpha = 0.35f))
            )
            .border(
                0.6.dp,
                Color.White.copy(alpha = if (canSend) 0.25f else 0.10f),
                shape
            )
            .clickable(enabled = canSend, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.ArrowUpward,
            contentDescription = stringResource(R.string.coach_send_a11y),
            tint = Color.White,
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun resizedJpeg(bytes: ByteArray, maxDimension: Int, quality: Int): ByteArray? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > maxDimension) {
        val ratio = maxDimension.toFloat() / longest.toFloat()
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    } else {
        bitmap
    }
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        out.toByteArray()
    }
}
