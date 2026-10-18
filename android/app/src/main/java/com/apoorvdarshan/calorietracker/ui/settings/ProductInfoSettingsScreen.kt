package com.apoorvdarshan.calorietracker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.ProductInfoField
import com.apoorvdarshan.calorietracker.services.FoodDataCentralService
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * Settings → App Preferences → Product information (#427), the Android port of iOS
 * ProductInfoSettingsView: turn off the barcode product details block or choose which
 * Open Food Facts fields it shows. Allergen check is not one of these fields. Also holds the
 * optional USDA FoodData Central key for barcodes Open Food Facts cannot use (#533).
 */
@Composable
fun ProductInfoSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    // null until DataStore answers, so a disabled block never flashes as enabled.
    val shown by container.prefs.productInfoShown.collectAsState<Boolean, Boolean?>(initial = null)
    val hidden by container.prefs.productInfoHiddenFields.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = BottomNavScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = AppColors.Calorie,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.nav_settings),
                        color = AppColors.Calorie,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.product_information),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            val isShown = shown ?: return@LazyColumn
            item {
                SectionCard {
                    ToggleRow(
                        label = stringResource(R.string.product_info_settings_show),
                        checked = isShown,
                        onChange = { value -> scope.launch { container.prefs.setProductInfoShown(value) } }
                    )
                }
            }
            item {
                Text(
                    stringResource(R.string.product_info_settings_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            if (isShown) {
                item {
                    SectionCard(title = stringResource(R.string.product_info_settings_fields)) {
                        ProductInfoField.entries.forEachIndexed { index, field ->
                            if (index > 0) HorizontalDivider()
                            ToggleRow(
                                label = stringResource(field.labelRes),
                                checked = field !in hidden,
                                onChange = { visible ->
                                    scope.launch { container.prefs.setProductInfoFieldHidden(field, !visible) }
                                }
                            )
                        }
                    }
                }
            }
            item { UsdaFoodDataCentralKeySection(container) }
        }
    }
}

/**
 * The optional USDA FoodData Central key for barcodes Open Food Facts cannot use (#533). It is
 * kept in the encrypted key store; with no key the app never contacts FoodData Central.
 */
@Composable
private fun UsdaFoodDataCentralKeySection(container: AppContainer) {
    val uriHandler = LocalUriHandler.current
    var key by remember { mutableStateOf(container.keyStore.usdaFoodDataCentralApiKey().orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionCard(title = stringResource(R.string.usda_fooddata_central)) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                FudGlassTextField(
                    value = key,
                    onValueChange = { value ->
                        key = value
                        container.keyStore.setUsdaFoodDataCentralApiKey(value)
                    },
                    placeholder = stringResource(R.string.usda_fdc_api_key_placeholder),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { uriHandler.openUri(FoodDataCentralService.API_KEY_SIGNUP_URL) }) {
                        Text(stringResource(R.string.usda_fdc_get_key), color = AppColors.Calorie)
                    }
                    Spacer(Modifier.weight(1f))
                    if (key.isNotEmpty()) {
                        TextButton(onClick = {
                            key = ""
                            container.keyStore.setUsdaFoodDataCentralApiKey(null)
                        }) {
                            Text(stringResource(R.string.settings_clear_key), color = AppColors.Calorie)
                        }
                    }
                }
            }
        }
        Text(
            stringResource(R.string.usda_fdc_settings_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}
