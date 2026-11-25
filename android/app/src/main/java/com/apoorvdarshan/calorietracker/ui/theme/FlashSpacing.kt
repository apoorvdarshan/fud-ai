package com.apoorvdarshan.calorietracker.ui.theme

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Layout density for app-owned Flash surfaces, independent of system font and display size. */
internal object FlashSpacing {
    val PageInset = 12.dp
    val SheetInset = 14.dp
    val SectionGap = 12.dp
    val ItemGap = 8.dp
    val MinTouchTarget = 48.dp
}

/** Apply inside an interactive row's click modifier so the entire target stays tappable. */
internal fun Modifier.flashListRow(): Modifier =
    heightIn(min = FlashSpacing.MinTouchTarget)
        .padding(horizontal = FlashSpacing.PageInset, vertical = 6.dp)
