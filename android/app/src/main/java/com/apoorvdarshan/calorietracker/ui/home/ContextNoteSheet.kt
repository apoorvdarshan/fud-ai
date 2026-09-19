package com.apoorvdarshan.calorietracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder
import com.apoorvdarshan.calorietracker.R

/** Camera review step. Photos stay as ordered independent byte arrays; the
 * optional note and complete photo set are sent as one meal request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPhotoCaptureSheet(
    imageBytesList: List<ByteArray>,
    addsFromLibrary: Boolean,
    onAddPhoto: () -> Unit,
    onRemove: (Int) -> Unit,
    onAnalyze: (String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    isBusy: Boolean = false,
    note: String = "",
    onNoteChange: (String) -> Unit = {},
    progressiveMeal: Boolean = false,
    onProgressiveMealChange: (Boolean) -> Unit = {}
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showProgressiveInfo by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        SheetReviewToolbar(
            title = "Meal Photos",
            primaryLabel = stringResource(R.string.action_analyze),
            primaryEnabled = !isBusy,
            onCancel = onDismiss,
            onPrimary = {
                onAnalyze(
                    note.takeIf { it.isNotBlank() },
                    progressiveMeal && imageBytesList.size > 1
                )
            }
        )

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                Text(
                    "${imageBytesList.size} of 10 photos",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)
            ) {
                itemsIndexed(imageBytesList, key = { index, bytes -> "$index-${bytes.size}" }) { index, bytes ->
                    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, bytes) {
                        value = withContext(Dispatchers.IO) {
                            FoodImageDecoder.decode(bytes, 720)
                        }
                    }
                    Box {
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = "Photo ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(width = 240.dp, height = 260.dp)
                                    .clip(RoundedCornerShape(20.dp))
                            )
                        }
                        IconButton(
                            onClick = { onRemove(index) },
                            enabled = !isBusy,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(34.dp)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.62f), androidx.compose.foundation.shape.CircleShape)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove photo", tint = androidx.compose.ui.graphics.Color.White)
                        }
                        Text(
                            "Photo ${index + 1}",
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(10.dp)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.58f), RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (imageBytesList.size < 10) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onAddPhoto, enabled = !isBusy) {
                        Icon(
                            if (addsFromLibrary) Icons.Filled.PhotoLibrary else Icons.Filled.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            if (addsFromLibrary) "Add Photos" else "Add Photo",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.progressive_meal_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(
                            onClick = { showProgressiveInfo = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(R.string.progressive_meal_info_title),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.progressive_meal_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                    )
                }
                Switch(
                    checked = progressiveMeal,
                    onCheckedChange = onProgressiveMealChange,
                    enabled = imageBytesList.size > 1
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheetSectionHeader("Note for food analysis (optional)")
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    placeholder = { Text("e.g. chicken is 180g, rice is 220g, use half the sauce") },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)
                )
            }
        }
    }

    if (showProgressiveInfo) {
        AlertDialog(
            onDismissRequest = { showProgressiveInfo = false },
            title = { Text(stringResource(R.string.progressive_meal_info_title)) },
            text = { Text(stringResource(R.string.progressive_meal_info_body)) },
            confirmButton = {
                TextButton(onClick = { showProgressiveInfo = false }) {
                    Text(stringResource(R.string.action_done))
                }
            }
        )
    }
}
