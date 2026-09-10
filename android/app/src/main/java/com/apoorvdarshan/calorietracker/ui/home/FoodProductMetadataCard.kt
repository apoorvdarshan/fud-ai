package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FoodProductMetadata
import com.apoorvdarshan.calorietracker.models.AllergenAnalysis

@Composable
internal fun FoodProductMetadataCard(
    metadata: FoodProductMetadata,
    allergenAnalysis: AllergenAnalysis? = null
) {
    val rows = buildList {
        add(ProductMetadataItem(stringResource(R.string.product_barcode), metadata.barcode))
        metadata.packageQuantity?.let {
            add(ProductMetadataItem(stringResource(R.string.product_package), it))
        }
        metadata.nutriScore?.let {
            add(ProductMetadataItem(stringResource(R.string.product_nutri_score), it))
        }
        metadata.novaGroup?.let {
            add(ProductMetadataItem(stringResource(R.string.product_nova_group), it.toString()))
        }
        metadata.ecoScore?.let {
            add(ProductMetadataItem(stringResource(R.string.product_eco_score), it))
        }
        metadata.allergens.takeIf { it.isNotEmpty() }?.let {
            add(
                ProductMetadataItem(
                    stringResource(R.string.product_allergens),
                    it.joinToString(", "),
                    stacked = true
                )
            )
        }
        metadata.traces.takeIf { it.isNotEmpty() }?.let {
            add(
                ProductMetadataItem(
                    stringResource(R.string.product_may_contain),
                    it.joinToString(", "),
                    stacked = true
                )
            )
        }
        allergenAnalysis?.let {
            add(ProductMetadataItem(stringResource(R.string.product_allergen_check), it.summary, stacked = true))
        }
        metadata.labels.takeIf { it.isNotEmpty() }?.let {
            add(
                ProductMetadataItem(
                    stringResource(R.string.product_labels),
                    it.joinToString(", "),
                    stacked = true
                )
            )
        }
        metadata.categories.takeIf { it.isNotEmpty() }?.let {
            add(
                ProductMetadataItem(
                    stringResource(R.string.product_categories),
                    it.joinToString(", "),
                    stacked = true
                )
            )
        }
    }

    SheetPillCard {
        rows.forEachIndexed { index, item ->
            if (index > 0) SheetHairline()
            ProductMetadataRow(item)
        }
        metadata.ingredientsText?.let { ingredients ->
            if (rows.isNotEmpty()) SheetHairline()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.product_ingredient_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    ingredients,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
            }
        }
    }
}

private data class ProductMetadataItem(
    val label: String,
    val value: String,
    val stacked: Boolean = false
)

@Composable
private fun ProductMetadataRow(item: ProductMetadataItem) {
    if (item.stacked) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                item.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                item.value,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            item.label,
            fontSize = 15.sp,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Text(
            item.value,
            modifier = Modifier.weight(1.6f),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
            fontSize = 15.sp,
            textAlign = TextAlign.End
        )
    }
}
