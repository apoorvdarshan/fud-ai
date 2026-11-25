package com.apoorvdarshan.calorietracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

private val facts = listOf(
    R.string.waiting_fact_vitamin_c,
    R.string.waiting_fact_beans,
    R.string.waiting_fact_oats,
    R.string.waiting_fact_vegetables,
    R.string.waiting_fact_strength
)

/** A bundled fact stays put while the analysis stages and elapsed time update. */
@Composable
internal fun WaitingFactCard(modifier: Modifier = Modifier) {
    val fact = rememberSaveable { facts.random() }
    val accent = AppColors.Calorie
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.09f), shape)
            .border(1.dp, accent.copy(alpha = 0.24f), shape)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("analysis.waitingFact")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.waiting_fact_heading),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            stringResource(fact),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
