package com.apoorvdarshan.calorietracker.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.billing.HostedAIConstants
import com.apoorvdarshan.calorietracker.billing.RevenueCatManager
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.models.StoreProduct
import kotlinx.coroutines.launch

private data class TipTier(val productId: String, val labelRes: Int)

@Composable
fun TipJarSettingsSection(revenueCat: RevenueCatManager) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val tiers = remember {
        listOf(
            TipTier(HostedAIConstants.TIP_SNACK, R.string.tip_snack),
            TipTier(HostedAIConstants.TIP_PROTEIN, R.string.tip_protein_shake),
            TipTier(HostedAIConstants.TIP_LUNCH, R.string.tip_lunch),
            TipTier(HostedAIConstants.TIP_FEAST, R.string.tip_feast)
        )
    }
    var products by remember { mutableStateOf<Map<String, StoreProduct>>(emptyMap()) }
    var purchasingId by remember { mutableStateOf<String?>(null) }
    var thankYou by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var purchaseError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!Purchases.isConfigured) return@LaunchedEffect
        runCatching {
            Purchases.sharedInstance.getProducts(
                tiers.map { it.productId },
                object : com.revenuecat.purchases.interfaces.GetStoreProductsCallback {
                    override fun onReceived(storeProducts: List<StoreProduct>) {
                        products = storeProducts.associateBy { it.id }
                    }

                    override fun onError(error: com.revenuecat.purchases.PurchasesError) {
                        loadError = error.message ?: context.getString(R.string.settings_tip_load_error)
                    }
                }
            )
        }.onFailure { error ->
            loadError = error.message ?: context.getString(R.string.settings_tip_load_error)
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.settings_tip_jar_title), style = MaterialTheme.typography.titleSmall)
        loadError?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        tiers.forEach { tier ->
            val product = products[tier.productId]
            TextButton(
                onClick = {
                    val act = activity ?: return@TextButton
                    val p = product ?: return@TextButton
                    scope.launch {
                        purchasingId = tier.productId
                        try {
                            revenueCat.purchaseProduct(act, p)
                            thankYou = true
                        } catch (error: Throwable) {
                            purchaseError = error.message ?: context.getString(R.string.settings_tip_purchase_error_title)
                        } finally {
                            purchasingId = null
                        }
                    }
                },
                enabled = product != null && purchasingId == null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(tier.labelRes))
                    when {
                        purchasingId == tier.productId -> CircularProgressIndicator()
                        product != null -> Text(product.price.formatted)
                        else -> Text(stringResource(R.string.settings_tip_unavailable))
                    }
                }
            }
        }
        if (thankYou) {
            Text(stringResource(R.string.settings_tip_thanks), color = MaterialTheme.colorScheme.primary)
        }
    }

    purchaseError?.let { message ->
        AlertDialog(
            onDismissRequest = { purchaseError = null },
            title = { Text(stringResource(R.string.settings_tip_purchase_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { purchaseError = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        )
    }
}
