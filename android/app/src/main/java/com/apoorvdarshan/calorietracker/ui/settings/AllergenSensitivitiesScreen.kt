package com.apoorvdarshan.calorietracker.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysisService
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialog
import com.apoorvdarshan.calorietracker.ui.components.FudGlassDialogActions
import com.apoorvdarshan.calorietracker.ui.components.FudGlassSurface
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextButton
import com.apoorvdarshan.calorietracker.ui.components.FudGlassTextField
import com.apoorvdarshan.calorietracker.ui.navigation.BottomNavScrollPadding
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

@Composable
fun AllergenSensitivitiesScreen(
    current: List<String>,
    onSave: (List<String>) -> Unit,
    onBack: () -> Unit,
    foodAnalysis: FoodAnalysisService
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var allergens by rememberSaveable { mutableStateOf(current) }
    var value by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var showImportSource by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<List<String>?>(null) }
    var selectedImport by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun persist(next: List<String>) {
        allergens = next
        onSave(next)
    }

    fun addCurrentValue() {
        val next = value.trim()
        value = ""
        if (next.isEmpty() || allergens.any { it.equals(next, ignoreCase = true) }) return
        persist(allergens + next)
    }

    fun removeAllergen(allergen: String) {
        persist(allergens.filterNot { it == allergen })
    }

    fun mergeImported(names: List<String>) {
        val next = allergens.toMutableList()
        val existing = next.mapTo(mutableSetOf()) { it.lowercase() }
        for (name in names) {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || !existing.add(trimmed.lowercase())) continue
            next += trimmed
        }
        persist(next)
    }

    fun clearPendingImport() {
        pendingImport = null
        selectedImport = emptySet()
    }

    fun toggleImportName(name: String) {
        selectedImport = if (name in selectedImport) selectedImport - name else selectedImport + name
    }

    fun runLabReportImport(loadImages: suspend () -> List<ByteArray>) {
        if (isImporting) return
        isImporting = true
        importError = null
        scope.launch {
            val result = runCatching {
                val images = withContext(Dispatchers.IO) { loadImages() }
                if (images.isEmpty()) error(context.getString(R.string.settings_allergen_import_read_failed))
                foodAnalysis.extractAllergensFromLabReport(images)
            }
            isImporting = false
            result.fold(
                onSuccess = { names ->
                    if (names.isEmpty()) {
                        importError = context.getString(R.string.settings_allergen_import_none_found)
                    } else {
                        selectedImport = names.toSet()
                        pendingImport = names
                    }
                },
                onFailure = { error ->
                    importError = error.message
                        ?: context.getString(R.string.settings_allergen_import_failed)
                }
            )
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runLabReportImport {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error(context.getString(R.string.settings_allergen_import_read_failed))
            listOf(bytes)
        }
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runLabReportImport {
            labReportImagesFromUri(context, uri)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = BottomNavScrollPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        stringResource(R.string.settings_allergen_sensitivities),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_allergen_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
                item {
                    FudGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.dp,
                        padding = 0.dp
                    ) {
                        Column {
                            if (allergens.isEmpty()) {
                                Text(
                                    stringResource(R.string.settings_allergen_empty),
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    fontSize = 16.sp
                                )
                            } else {
                                allergens.forEachIndexed { index, allergen ->
                                    key(allergen) {
                                        AllergenSwipeRow(
                                            allergen = allergen,
                                            onRequestDelete = { pendingDelete = allergen }
                                        )
                                    }
                                    if (index != allergens.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    FudGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.dp,
                        padding = 16.dp
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.settings_allergen_import_section),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                            FudGlassTextButton(
                                text = stringResource(R.string.settings_allergen_import_button),
                                onClick = { showImportSource = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isImporting
                            )
                            Text(
                                stringResource(R.string.settings_allergen_import_disclaimer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
                item {
                    FudGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 22.dp,
                        padding = 16.dp
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.settings_allergen_add_section),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                            FudGlassTextField(
                                value = value,
                                onValueChange = { value = it },
                                placeholder = stringResource(R.string.settings_allergen_add_placeholder),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { addCurrentValue() })
                            )
                            Text(
                                stringResource(R.string.settings_allergen_add_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                    }
                }
            }

            if (isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center
                ) {
                    FudGlassSurface(cornerRadius = 20.dp, padding = 20.dp) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = AppColors.Calorie,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                stringResource(R.string.settings_allergen_import_loading),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { allergen ->
        FudGlassDialog(onDismissRequest = { pendingDelete = null }) {
            Text(
                stringResource(R.string.settings_allergen_remove_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.settings_allergen_remove_message, allergen),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_delete),
                onPrimary = {
                    removeAllergen(allergen)
                    pendingDelete = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { pendingDelete = null },
                destructive = true
            )
        }
    }

    if (showImportSource) {
        FudGlassDialog(onDismissRequest = { showImportSource = false }) {
            Text(
                stringResource(R.string.settings_allergen_import_button),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.settings_allergen_import_source_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FudGlassTextButton(
                    text = stringResource(R.string.settings_allergen_import_choose_photo),
                    onClick = {
                        showImportSource = false
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                FudGlassTextButton(
                    text = stringResource(R.string.settings_allergen_import_choose_file),
                    onClick = {
                        showImportSource = false
                        documentPicker.launch(arrayOf("image/*", "application/pdf"))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                FudGlassDialogActions(
                    primaryText = stringResource(R.string.action_cancel),
                    onPrimary = { showImportSource = false }
                )
            }
        }
    }

    pendingImport?.let { candidates ->
        val allSelected = candidates.isNotEmpty() && selectedImport.containsAll(candidates)
        FudGlassDialog(onDismissRequest = { clearPendingImport() }) {
            Text(
                stringResource(R.string.settings_allergen_import_confirm_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.settings_allergen_import_confirm_message),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            FudGlassTextButton(
                text = stringResource(
                    if (allSelected) R.string.settings_allergen_import_deselect_all
                    else R.string.settings_allergen_import_select_all
                ),
                onClick = {
                    selectedImport = if (allSelected) emptySet() else candidates.toSet()
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                candidates.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { toggleImportName(name) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = name in selectedImport,
                            onCheckedChange = { checked ->
                                selectedImport = if (checked) {
                                    selectedImport + name
                                } else {
                                    selectedImport - name
                                }
                            }
                        )
                        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            FudGlassDialogActions(
                primaryText = stringResource(R.string.settings_allergen_import_add_selected),
                onPrimary = {
                    mergeImported(candidates.filter { it in selectedImport })
                    clearPendingImport()
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { clearPendingImport() },
                primaryEnabled = selectedImport.isNotEmpty()
            )
        }
    }

    importError?.let { message ->
        FudGlassDialog(onDismissRequest = { importError = null }) {
            Text(
                stringResource(R.string.settings_allergen_import_error_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            FudGlassDialogActions(
                primaryText = stringResource(R.string.action_ok),
                onPrimary = { importError = null }
            )
        }
    }
}

private fun labReportImagesFromUri(context: Context, uri: Uri): List<ByteArray> {
    val mime = context.contentResolver.getType(uri).orEmpty()
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error(context.getString(R.string.settings_allergen_import_read_failed))
    val isPdf = mime.equals("application/pdf", ignoreCase = true) ||
        (uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true)
    return if (isPdf) {
        rasterizePdfPages(context, bytes, maxPages = 3)
    } else {
        listOf(bytes)
    }
}

private fun rasterizePdfPages(context: Context, bytes: ByteArray, maxPages: Int): List<ByteArray> {
    val tmp = File.createTempFile("lab_report", ".pdf", context.cacheDir)
    try {
        tmp.writeBytes(bytes)
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount <= 0) return emptyList()
                val scale = 2
                (0 until minOf(renderer.pageCount, maxPages)).map { index ->
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * scale,
                            page.height * scale,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(
                            bitmap,
                            null,
                            Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) },
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        ByteArrayOutputStream().also { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            bitmap.recycle()
                        }.toByteArray()
                    }
                }
            }
        }
    } finally {
        tmp.delete()
    }
}

/**
 * Home-diary-style trailing swipe: drag past threshold then release to request delete.
 * Avoids Material SwipeToDismissBox's broken confirmValueChange snap-back.
 */
@Composable
private fun AllergenSwipeRow(
    allergen: String,
    onRequestDelete: () -> Unit
) {
    val density = LocalDensity.current
    val deleteTriggerPx = with(density) { 220.dp.toPx() }
    var offsetPx by remember(allergen) { mutableFloatStateOf(0f) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val rowBg = if (isDark) Color(0xFF17171B) else Color(0xFFFAF2EC)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxSwipePx = with(density) { maxWidth.toPx() * 0.72f }
        Box(Modifier.fillMaxWidth()) {
            if (offsetPx < 0f) {
                val widthDp = with(density) { (-offsetPx).toDp() }
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(widthDp)
                            .background(Color(0xFFD32F2F)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (-offsetPx > 24f) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .offset { IntOffset(offsetPx.roundToInt(), 0) }
                    .fillMaxWidth()
                    .background(rowBg)
                    .pointerInput(allergen, maxSwipePx) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(-maxSwipePx, 0f)
                            },
                            onDragEnd = {
                                val shouldDelete = offsetPx <= -deleteTriggerPx
                                offsetPx = 0f
                                if (shouldDelete) onRequestDelete()
                            },
                            onDragCancel = { offsetPx = 0f }
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    allergen,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
