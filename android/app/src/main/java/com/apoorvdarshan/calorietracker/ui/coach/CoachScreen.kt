package com.apoorvdarshan.calorietracker.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachScreen(container: AppContainer) {
    val vm: CoachViewModel = viewModel(factory = CoachViewModel.Factory(container))
    val ui by vm.ui.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Coach", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    IconButton(onClick = { vm.resetConversation() }) {
                        Icon(Icons.Filled.Refresh, "Reset conversation", tint = AppColors.Calorie)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (ui.messages.isEmpty()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SparkleBadge(size = 64.dp, iconSize = 32.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Ask me anything about your data",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "I can see your profile, weights, food log, and forecast — and answer in plain English.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        for (s in ui.suggestions) PromptChip(s) { vm.send(s) }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(ui.messages, key = { it.id }) { MessageBubble(it) }
                    if (ui.sending) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SparkleBadge(size = 26.dp, iconSize = 11.dp)
                                Spacer(Modifier.width(8.dp))
                                CircularProgressIndicator(
                                    color = AppColors.Calorie,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Thinking…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }

            ComposerBar(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    if (input.isNotBlank()) {
                        vm.send(input.trim())
                        input = ""
                    }
                }
            )
        }
    }

    ui.error?.let { err ->
        AlertDialog(
            onDismissRequest = { vm.dismissError() },
            title = { Text("Chat error") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = { vm.dismissError() }) { Text("OK") } }
        )
    }
}

@Composable
private fun SparkleBadge(size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = AppColors.Calorie
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            SparkleBadge(size = 26.dp, iconSize = 11.dp)
            Spacer(Modifier.width(6.dp))
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Bubble(content = msg.content, isUser = isUser)
        if (!isUser) {
            Spacer(Modifier.width(48.dp))
        }
    }
}

@Composable
private fun Bubble(content: String, isUser: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    val bg: Brush = if (isUser) {
        AppColors.CalorieGradient
    } else {
        Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
    }
    val border: Brush = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isUser) 0.45f else 0.22f),
            Color.White.copy(alpha = 0.05f)
        )
    )
    val textColor = if (isUser) Color.White else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .shadow(
                elevation = if (isUser) 8.dp else 3.dp,
                shape = shape,
                ambientColor = if (isUser) AppColors.Calorie else Color.Black,
                spotColor = if (isUser) AppColors.Calorie else Color.Black
            )
            .clip(shape)
            .background(bg)
            .border(0.8.dp, border, shape)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Text(
            content,
            fontSize = 15.sp,
            color = textColor,
            lineHeight = 20.sp,
            style = TextStyle(fontWeight = FontWeight.Normal)
        )
    }
}

@Composable
private fun PromptChip(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(AppColors.Calorie.copy(alpha = 0.10f))
            .border(0.7.dp, AppColors.Calorie.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = AppColors.Calorie, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppColors.Calorie)
    }
}

@Composable
private fun ComposerBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Ask something…") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp)
        )
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (value.isNotBlank()) AppColors.CalorieGradient else Brush.linearGradient(listOf(Color.Gray.copy(alpha = 0.2f), Color.Gray.copy(alpha = 0.2f))))
                .clickable(enabled = value.isNotBlank(), onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
