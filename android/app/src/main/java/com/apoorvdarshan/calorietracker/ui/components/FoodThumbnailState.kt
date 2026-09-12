package com.apoorvdarshan.calorietracker.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.apoorvdarshan.calorietracker.services.FoodImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a food photo thumbnail off the composition thread; shows null until ready. */
@Composable
fun rememberFoodThumbnail(
    imageStore: FoodImageStore,
    filename: String?,
): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, filename, imageStore) {
        value = filename?.let { name ->
            withContext(Dispatchers.IO) {
                imageStore.loadThumbnail(name)
            }
        }
    }
    return bitmap
}

/** Loads the first available thumbnail from [filenames] off the composition thread. */
@Composable
fun rememberFoodThumbnail(
    imageStore: FoodImageStore,
    filenames: List<String>,
): Bitmap? {
    val bitmap by produceState<Bitmap?>(initialValue = null, filenames, imageStore) {
        value = withContext(Dispatchers.IO) {
            filenames.firstNotNullOfOrNull { imageStore.loadThumbnail(it) }
        }
    }
    return bitmap
}
