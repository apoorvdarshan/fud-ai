package com.apoorvdarshan.calorietracker.ui.coach

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder
import com.apoorvdarshan.calorietracker.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.ui.components.InAppCameraCaptureDialog
import com.apoorvdarshan.calorietracker.models.SpeechLanguage
import com.apoorvdarshan.calorietracker.models.SpeechProvider
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavDockedControlPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CoachScreen(container: AppContainer) {
    val vm: CoachViewModel = viewModel(factory = CoachViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    var attachedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showCameraCapture by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var showResetConfirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // Dismiss the keyboard when the USER drags the chat (DragInteraction only —
    // the auto-scroll after sending a message must not steal focus).
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }
    }

    fun hideKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: return@launch
                attachedImageBytes = resizedJpeg(bytes, maxDimension = 1800, quality = 86) ?: bytes
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCameraCapture = true
    }

    fun openCamera() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showCameraCapture = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun sendCurrentDraft(textOverride: String? = null) {
        val image = attachedImageBytes
        val trimmed = (textOverride ?: input).trim()
        if (trimmed.isEmpty() && image == null) return
        if (ui.sending) return
        hideKeyboard()
        input = ""
        attachedImageBytes = null
        scope.launch {
            val imageForAi = image?.let { resizedJpeg(it, maxDimension = 1600, quality = 78) ?: it }
            val thumbnail = image?.let { resizedJpeg(it, maxDimension = 700, quality = 68) ?: it }
            vm.send(trimmed, imageBytes = imageForAi, thumbnailBytes = thumbnail)
        }
    }

    // Inline (WhatsApp-style) voice recorder — records with whatever STT provider
    // the user has configured and drops the transcript straight into the send path.
    val voiceScope = rememberCoroutineScope()
    val voiceProvider by container.prefs.selectedSpeechProvider
        .collectAsState(initial = SpeechProvider.NATIVE)
    val voiceLanguage by container.prefs.selectedSpeechLanguage(voiceProvider)
        .collectAsState(initial = SpeechLanguage.defaultFor(voiceProvider))
    val voice = remember { CoachVoiceController(ctx, container, voiceScope) { text -> sendCurrentDraft(text) } }
    LaunchedEffect(voiceProvider, voiceLanguage) {
        voice.provider = voiceProvider
        voice.nativeLocale = voiceLanguage.nativeLocaleTag()
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
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f))
                            .clickable(enabled = canReset) { showResetConfirm = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Replay,
                            contentDescription = stringResource(R.string.coach_reset_chat_a11y),
                            tint = if (canReset)
                                AppColors.Calorie
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        // The app is edge-to-edge, so the IME would otherwise overlay the input bar.
        // Lift the whole column above the keyboard (imePadding) with a small gap; when
        // the keyboard is down, keep the docked-nav clearance instead.
        // Keyboard-down clearance = the nav-bar system inset (from the Scaffold) plus the
        // docked-control padding, so the bar clears the floating bottom nav.
        val restClearance = padding.calculateBottomPadding() + BottomNavDockedControlPadding
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                // Track the keyboard rigidly: bottom inset = max(ime, rest clearance).
                // windowInsetsPadding animates it in the layout phase, so the bar sits
                // tight on the keyboard with no bounce and no floaty gap (a plain
                // conditional pad jumps discretely against the smooth IME animation).
                .windowInsetsPadding(
                    WindowInsets.ime
                        .union(WindowInsets(bottom = restClearance))
                        .only(WindowInsetsSides.Bottom)
                )
        ) {
            val resolvedChips = ui.suggestions.map { stringResource(it) }
            val onPromptTap: (String) -> Unit = { chip ->
                hideKeyboard()
                input = ""
                attachedImageBytes = null
                vm.send(chip)
            }

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
                    EmptyState(
                        prompts = resolvedChips,
                        enabled = !ui.sending,
                        onPromptTap = onPromptTap,
                        modifier = Modifier.fillMaxSize()
                    )
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

            if (ui.messages.isNotEmpty()) {
                PromptChipRow(
                    chips = resolvedChips,
                    enabled = !ui.sending,
                    onTap = onPromptTap
                )
            }

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
                voice = voice,
                onRemoveImage = { attachedImageBytes = null },
                onSend = { sendCurrentDraft() }
            )
        }
    }

    if (showCameraCapture) {
        InAppCameraCaptureDialog(
            onCapture = { bytes ->
                showCameraCapture = false
                scope.launch {
                    attachedImageBytes = resizedJpeg(bytes, maxDimension = 1800, quality = 86) ?: bytes
                }
            },
            onDismiss = { showCameraCapture = false }
        )
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
private fun EmptyState(
    prompts: List<String>,
    enabled: Boolean,
    onPromptTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(top = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            Modifier
                .size(92.dp)
                .shadow(
                    elevation = 14.dp,
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
                modifier = Modifier.size(38.dp),
                tint = AppColors.Calorie
            )
        }
        Spacer(Modifier.height(18.dp))
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
            lineHeight = 21.sp,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(26.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompts.take(4).chunked(2).forEach { rowPrompts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    rowPrompts.forEach { prompt ->
                        EmptyPromptCard(
                            text = prompt,
                            enabled = enabled,
                            onTap = { onPromptTap(prompt) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (rowPrompts.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EmptyPromptCard(
    text: String,
    enabled: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onTap,
        enabled = enabled,
        modifier = modifier.heightIn(min = 70.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(0.6.dp, AppColors.Calorie.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .background(AppColors.Calorie.copy(alpha = 0.045f))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Filled.NorthEast,
                contentDescription = null,
                tint = AppColors.Calorie,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 3
            )
        }
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
        contentPadding = PaddingValues(top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(messages, key = { it.id }) { MessageBubble(it) }

        if (sending) {
            item("typing") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AssistantBadge()
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                            .border(
                                0.5.dp,
                                Color.White.copy(alpha = 0.15f),
                                RoundedCornerShape(topStart = 8.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) { TypingIndicator() }
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        if (error != null) {
            item("error") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                        .background(Color(0xFFD32F2F).copy(alpha = 0.06f))
                        .border(0.6.dp, Color(0xFFD32F2F).copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFD32F2F),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(error, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFD32F2F))
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
            .size(28.dp)
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
    val shape = RoundedCornerShape(
        topStart = if (isUser) 20.dp else 8.dp,
        topEnd = if (isUser) 8.dp else 20.dp,
        bottomEnd = 20.dp,
        bottomStart = 20.dp
    )
    val borderBrush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isUser) 0.45f else 0.22f),
            Color.White.copy(alpha = if (isUser) 0.05f else 0.04f)
        )
    )
    val shadowElevation = if (isUser) 9.dp else 5.dp
    val shadowColor = if (isUser) AppColors.Calorie.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.08f)

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
                val bitmap by produceState<Bitmap?>(initialValue = null, encoded) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes = Base64.getDecoder().decode(encoded)
                            FoodImageDecoder.decode(bytes, COACH_BUBBLE_IMAGE_MAX_DIMENSION)
                        }.getOrNull()
                    }
                }
                bitmap?.let { attachmentBitmap ->
                    Image(
                        bitmap = attachmentBitmap.asImageBitmap(),
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
            if (isUser) {
                // User's own typed text — show verbatim, no markdown.
                Text(
                    content,
                    fontSize = 17.sp,
                    color = Color.White,
                    lineHeight = 22.sp,
                    style = TextStyle(fontWeight = FontWeight.Normal)
                )
            } else {
                // Coach replies often use markdown — render it.
                MarkdownText(content = content, color = MaterialTheme.colorScheme.onSurface)
            }
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "prompt-sparkle") {
            Box(
                modifier = Modifier.width(32.dp).height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AppColors.Calorie.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
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
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
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
    voice: CoachVoiceController,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit
) {
    val canSend = !sending && (value.trim().isNotEmpty() || attachedImageBytes != null)
    val capsule = RoundedCornerShape(24.dp)

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
            val previewBitmap by produceState<Bitmap?>(initialValue = null, bytes) {
                this.value = withContext(Dispatchers.IO) {
                    FoodImageDecoder.decode(bytes, COACH_COMPOSER_PREVIEW_MAX_DIMENSION)
                }
            }
            previewBitmap?.let { attachmentBitmap ->
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)
                        .size(width = 88.dp, height = 70.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Image(
                        bitmap = attachmentBitmap.asImageBitmap(),
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
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_remove_image), tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (voice.phase != VoicePhase.Idle) {
                // Recording: the media pill + text field are replaced by the live
                // recording indicator (timer + slide-to-cancel hint / live text).
                CoachRecordingIndicator(voice, Modifier.weight(1f))
            } else {
                CoachMediaActions(
                    enabled = !sending,
                    onPickImage = onPickImage,
                    onCaptureImage = onCaptureImage
                )

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
            }

            // Trailing control. Keep the mic at a stable call site (the else branch)
            // so a held press survives the left region swapping to the indicator.
            when {
                voice.phase == VoicePhase.Locked -> {
                    CoachVoiceCancelButton { voice.cancel() }
                    SendButton(canSend = true) { voice.stopAndSend() }
                }
                voice.phase == VoicePhase.Transcribing -> Unit
                canSend -> SendButton(canSend = canSend, onClick = onSend)
                else -> CoachMicButton(voice)
            }
        }
    }
}

@Composable
private fun CoachMediaActions(
    enabled: Boolean,
    onPickImage: () -> Unit,
    onCaptureImage: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoachMediaActionButton(
            icon = Icons.Filled.PhotoLibrary,
            contentDescription = stringResource(R.string.cd_add_image),
            enabled = enabled,
            onClick = onPickImage
        )
        CoachMediaActionButton(
            icon = Icons.Filled.CameraAlt,
            contentDescription = stringResource(R.string.cd_open_camera),
            enabled = enabled,
            onClick = onCaptureImage
        )
    }
}

@Composable
private fun CoachMediaActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (enabled) AppColors.Calorie.copy(alpha = 0.11f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
            )
            .border(
                0.7.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (enabled) 0.18f else 0.08f),
                        AppColors.Calorie.copy(alpha = if (enabled) 0.2f else 0.06f)
                    )
                ),
                CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SendButton(canSend: Boolean, onClick: () -> Unit) {
    val size: Dp = 40.dp
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

private suspend fun resizedJpeg(bytes: ByteArray, maxDimension: Int, quality: Int): ByteArray? =
    withContext(Dispatchers.Default) {
        val scaled = FoodImageDecoder.decode(bytes, maxDimension) ?: return@withContext null
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
            out.toByteArray()
        }
    }

private const val COACH_BUBBLE_IMAGE_MAX_DIMENSION = 700
private const val COACH_COMPOSER_PREVIEW_MAX_DIMENSION = 280

// ── Markdown rendering for Coach replies ────────────────────────────────
// Lightweight renderer for the formatting the Coach actually emits: #/##/### headings,
// "- / * / 1." lists, ``` code fences ```, `inline code`, **bold**, *italic*, [links](url).
// Block layout here; inline styling via AnnotatedString. No third-party dependency.

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Numbered(val number: String, val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trim()
        when {
            trimmed.startsWith("```") -> {
                val code = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.add(lines[i]); i++
                }
                i++ // skip closing fence
                blocks.add(MdBlock.Code(code.joinToString("\n")))
            }
            trimmed.isEmpty() -> i++
            headingLevel(trimmed) != null -> {
                val level = headingLevel(trimmed)!!
                blocks.add(MdBlock.Heading(level, trimmed.trimStart('#').trim()))
                i++
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                blocks.add(MdBlock.Bullet(trimmed.drop(2).trim())); i++
            }
            numberedItem(trimmed) != null -> {
                val (num, rest) = numberedItem(trimmed)!!
                blocks.add(MdBlock.Numbered(num, rest)); i++
            }
            else -> { blocks.add(MdBlock.Paragraph(trimmed)); i++ }
        }
    }
    return blocks
}

private fun headingLevel(s: String): Int? {
    val hashes = s.takeWhile { it == '#' }.length
    if (hashes in 1..3 && s.getOrNull(hashes) == ' ') return hashes
    return null
}

private fun numberedItem(s: String): Pair<String, String>? {
    val dot = s.indexOf('.')
    if (dot <= 0) return null
    val num = s.substring(0, dot)
    if (!num.all { it.isDigit() } || s.getOrNull(dot + 1) != ' ') return null
    return num to s.substring(dot + 1).trim()
}

/** Inline markdown → AnnotatedString: **bold**, *italic* / _italic_, `code`, [text](url). */
private fun inlineMarkdown(text: String, linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        val c = text[i]
        when {
            c == '*' && i + 1 < n && text[i + 1] == '*' -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(c); i++ }
            }
            (c == '*' || c == '_') -> {
                val end = text.indexOf(c, i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf(']', i + 1)
                val open = if (close != -1) close + 1 else -1
                if (close != -1 && text.getOrNull(open) == '(') {
                    val urlEnd = text.indexOf(')', open + 1)
                    if (urlEnd != -1) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(text.substring(i + 1, close))
                        }
                        i = urlEnd + 1
                    } else { append(c); i++ }
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

@Composable
private fun MarkdownText(content: String, color: Color) {
    val linkColor = AppColors.Calorie
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, linkColor, codeBg),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (block.level) { 1 -> 20.sp; 2 -> 18.sp; else -> 16.sp },
                    lineHeight = 24.sp
                )
                is MdBlock.Bullet -> Row {
                    Text("•", color = color, fontSize = 17.sp, lineHeight = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(inlineMarkdown(block.text, linkColor, codeBg), color = color, fontSize = 17.sp, lineHeight = 22.sp)
                }
                is MdBlock.Numbered -> Row {
                    Text("${block.number}.", color = color, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(inlineMarkdown(block.text, linkColor, codeBg), color = color, fontSize = 17.sp, lineHeight = 22.sp)
                }
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(codeBg).padding(10.dp)
                ) {
                    Text(block.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)
                }
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(block.text, linkColor, codeBg),
                    color = color, fontSize = 17.sp, lineHeight = 22.sp
                )
            }
        }
    }
}
