package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

// The presentation adapters preserve the host's dismissal policy, gestures,
// focus and sheet state. Flash owns the frame and fill, not where a sheet goes.
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER") // Old call sites may still supply a surface or shape.
@Composable
internal fun FlashBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    sheetGesturesEnabled: Boolean = true,
    shape: Shape = RectangleShape,
    containerColor: Color = Color.Unspecified,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    properties: ModalBottomSheetProperties = ModalBottomSheetProperties(),
    content: @Composable ColumnScope.() -> Unit
) {
    val neo = pinkNeoPalette()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.border(1.dp, neo.rule),
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = RectangleShape,
        containerColor = neo.panel,
        contentColor = neo.ink,
        tonalElevation = 0.dp,
        dragHandle = dragHandle,
        properties = properties,
        content = content
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun FlashAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = RectangleShape
) {
    val neo = pinkNeoPalette()
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(1.dp, neo.rule),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = RectangleShape,
        containerColor = neo.panel,
        titleContentColor = neo.ink,
        textContentColor = neo.ink,
        tonalElevation = 0.dp
    )
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun FlashDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    val neo = pinkNeoPalette()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.border(1.dp, neo.rule),
        shape = RectangleShape,
        containerColor = neo.panel,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content
    )
}
