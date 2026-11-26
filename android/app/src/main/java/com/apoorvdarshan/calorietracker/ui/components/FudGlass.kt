package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.theme.FlashSpacing
import com.apoorvdarshan.calorietracker.ui.theme.PinkNeoCondensed
import com.apoorvdarshan.calorietracker.ui.theme.pinkNeoPalette

@Composable
fun FudGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: Dp = 16.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(cornerRadius)
    // The palette's own card color, so Dark gray cards follow the gray palette
    // instead of the fixed near-black fill. The sheen and border stay.
    val baseColor = MaterialTheme.colorScheme.surface.copy(alpha = if (isDark) 0.84f else 0.98f)
    val shadowColor = if (isDark) Color.Black.copy(alpha = 0.28f)
                      else Color.Black.copy(alpha = 0.11f)
    val sheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.070f else 0.34f),
            Color.White.copy(alpha = if (isDark) 0.018f else 0.08f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.026f else 0.045f)
        )
    )
    val border = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = if (isDark) 0.18f else 0.78f),
            Color.White.copy(alpha = if (isDark) 0.045f else 0.28f),
            AppColors.Calorie.copy(alpha = if (isDark) 0.075f else 0.14f)
        )
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isDark) 14.dp else 10.dp,
                shape = shape,
                ambientColor = shadowColor,
                spotColor = shadowColor
            )
            .clip(shape)
            .background(baseColor)
            .background(sheen)
            .border(0.8.dp, border, shape)
            .padding(padding),
        contentAlignment = contentAlignment,
        content = content
    )
}

@Composable
fun FudGlassColumn(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    padding: Dp = FlashSpacing.PageInset,
    content: @Composable ColumnScope.() -> Unit
) {
    FudGlassSurface(
        modifier = modifier,
        cornerRadius = cornerRadius,
        padding = 0.dp
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun FudIconBubble(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 19.dp,
    tint: Color = AppColors.Calorie
) {
    val plainIconSize = if (iconSize < size * 0.88f) size * 0.88f else iconSize
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(plainIconSize)
        )
    }
}

@Composable
fun FudGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = TextStyle(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium
    )
) {
    val neo = pinkNeoPalette()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = textStyle,
        cursorBrush = SolidColor(AppColors.Calorie),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) FlashSpacing.MinTouchTarget else 88.dp)
            .background(neo.paper)
            .border(1.dp, neo.rule)
            .padding(horizontal = FlashSpacing.PageInset, vertical = 10.dp),
        decorationBox = { inner ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty() && placeholder.isNotBlank()) {
                    Text(
                        placeholder,
                        color = neo.ink.copy(alpha = 0.64f),
                        style = textStyle
                    )
                }
                inner()
            }
        }
    )
}

@Composable
fun FudGlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        FudGlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = FlashSpacing.SheetInset),
            cornerRadius = 28.dp,
            padding = FlashSpacing.SheetInset
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(FlashSpacing.SectionGap),
                content = content
            )
        }
    }
}

@Composable
fun FudGlassPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    height: Dp = FlashSpacing.MinTouchTarget,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val neo = pinkNeoPalette()
    Row(
        modifier
            .heightIn(min = height.coerceAtLeast(FlashSpacing.MinTouchTarget))
            .background(if (enabled) neo.action else neo.panel)
            .border(1.dp, neo.rule)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (content != null) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides if (enabled) neo.onAction else neo.ink
            ) { content() }
        } else {
            Text(text, color = if (enabled) neo.onAction else neo.ink.copy(alpha = 0.60f),
                fontWeight = FontWeight.Black, fontSize = 16.sp, style = PinkNeoCondensed)
        }
    }
}

@Composable
fun FudGlassTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppColors.Calorie,
    enabled: Boolean = true
) {
    val neo = pinkNeoPalette()
    val ink = if (color == AppColors.Calorie) neo.ink else color
    Box(
        modifier
            .background(neo.panel)
            .border(1.dp, neo.rule)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = FlashSpacing.MinTouchTarget)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = ink.copy(alpha = if (enabled) ink.alpha else ink.alpha * 0.55f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FudGlassDialogActions(
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    primaryEnabled: Boolean = true
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically
    ) {
        if (dismissText != null && onDismiss != null) {
            FudGlassTextButton(
                text = dismissText,
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
            )
        }
        if (destructive) {
            FudGlassTextButton(
                text = primaryText,
                onClick = onPrimary,
                color = MaterialTheme.colorScheme.error,
                enabled = primaryEnabled
            )
        } else {
            FudGlassPrimaryButton(
                text = primaryText,
                onClick = onPrimary,
                modifier = Modifier,
                enabled = primaryEnabled
            )
        }
    }
}
