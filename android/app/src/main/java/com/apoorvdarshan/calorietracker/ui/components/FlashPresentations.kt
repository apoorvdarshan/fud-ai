package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

@Composable
internal fun FlashSheetAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val neo = pinkNeoPalette()
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, neo.rule),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) neo.action else neo.panel,
            contentColor = if (primary) neo.onAction else neo.ink,
            disabledContainerColor = neo.panel,
            disabledContentColor = neo.ink.copy(alpha = 0.6f)
        ),
        content = content
    )
}

// The presentation adapters preserve the host's dismissal policy, gestures,
// focus and sheet state. Keep the modal sheet modifier unframed: Material
// applies its vertical offset inside it, so a border here floats over the app.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlashBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    containerColor: Color = Color.Unspecified,
    dragHandle: @Composable (() -> Unit)? = { CompactSheetHandle() },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val neo = pinkNeoPalette()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = shape,
        containerColor = if (containerColor == Color.Unspecified) neo.panel else containerColor,
        contentColor = neo.ink,
        tonalElevation = 0.dp,
        dragHandle = dragHandle,
        properties = properties,
        content = content
    )
}

@Composable
private fun CompactSheetHandle() {
    val neo = pinkNeoPalette()
    Box(Modifier.padding(top = 9.dp, bottom = 8.dp)) {
        Box(Modifier.size(width = 28.dp, height = 3.dp).background(neo.ink.copy(alpha = 0.36f)))
    }
}

@Composable
internal fun FlashAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(24.dp)
) {
    val neo = pinkNeoPalette()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = shape,
        containerColor = neo.panel,
        titleContentColor = neo.ink,
        textContentColor = neo.ink,
        tonalElevation = 0.dp
    )
}

@Composable
internal fun FlashLoadingDialog(
    onDismissRequest: () -> Unit,
    dismissOnBackPress: Boolean = true
) {
    val neo = pinkNeoPalette()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier.size(76.dp).background(neo.panel, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = neo.accent, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
internal fun FlashDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val neo = pinkNeoPalette()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = shape,
        containerColor = if (containerColor == Color.Unspecified) neo.panel else containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, neo.rule),
        content = content
    )
}
