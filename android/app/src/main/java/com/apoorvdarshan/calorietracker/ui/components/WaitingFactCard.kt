package com.apoorvdarshan.calorietracker.ui.components

import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val language = WaitingFactCatalog.language(locale)
    var token by rememberSaveable { mutableStateOf("") }
    var selection by remember(language) { mutableStateOf<WaitingFactFeed.Selection?>(null) }
    LaunchedEffect(language) {
        val selected = withContext(Dispatchers.IO) { WaitingFactFeed.select(context, language, token) }
        token = selected.token
        selection = selected
    }
    val fact = selection ?: return
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
        val measurement = fact.measurement
        if (measurement == null) {
            Text(
                stringResource(facts[fact.index]),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Serif,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            MeasurementText(measurement, locale)
        }
    }
}

@Composable
private fun MeasurementText(entry: WaitingFactCatalog.Measurement, locale: Locale) {
    val formatted = remember(entry.amount, entry.nutrient, locale) {
        val unit = when (entry.nutrient.unit) {
            WaitingFactCatalog.Unit.GRAM -> MeasureUnit.GRAM
            WaitingFactCatalog.Unit.MILLIGRAM -> MeasureUnit.MILLIGRAM
            WaitingFactCatalog.Unit.MICROGRAM -> MeasureUnit.MICROGRAM
        }
        val numbers = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
        MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, numbers)
            .formatMeasures(Measure(entry.amount, unit))
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            entry.food, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface
        )
        preparationLabel(entry.preparation)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(R.string.waiting_fact_measure_per_100g, stringResource(entry.nutrient.label), formatted),
            style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    Text("USDA FoodData Central", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun preparationLabel(key: String): String? {
    val id = when (key) {
        "raw" -> R.string.waiting_fact_prep_raw
        "raw_skin" -> R.string.waiting_fact_prep_raw_skin
        "dried" -> R.string.waiting_fact_prep_dried
        "boiled_no_salt" -> R.string.waiting_fact_prep_boiled_no_salt
        "cooked_no_salt" -> R.string.waiting_fact_prep_cooked_no_salt
        "cooked" -> R.string.waiting_fact_prep_cooked
        "baked_skin_no_salt" -> R.string.waiting_fact_prep_baked_skin_no_salt
        "baked_flesh_no_salt" -> R.string.waiting_fact_prep_baked_flesh_no_salt
        "roasted_no_salt" -> R.string.waiting_fact_prep_roasted_no_salt
        "hard_boiled" -> R.string.waiting_fact_prep_hard_boiled
        "plain_whole_milk" -> R.string.waiting_fact_prep_plain_whole_milk
        "roasted" -> R.string.waiting_fact_prep_roasted
        "no_salt" -> R.string.waiting_fact_prep_no_salt
        else -> return null
    }
    return stringResource(id)
}
