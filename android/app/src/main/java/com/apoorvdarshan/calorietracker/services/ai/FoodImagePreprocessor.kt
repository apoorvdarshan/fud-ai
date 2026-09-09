package com.apoorvdarshan.calorietracker.services.ai

import android.graphics.Bitmap
import com.apoorvdarshan.calorietracker.services.FoodImageDecoder
import java.io.ByteArrayOutputStream

/** Prepares food photos for vision requests without changing the locally stored original. */
internal object FoodImagePreprocessor {
    private const val MAX_DIMENSION = 1_600
    private const val JPEG_QUALITY = 80

    fun prepareForUpload(bytes: ByteArray): ByteArray = runCatching {
        val bitmap = FoodImageDecoder.decode(bytes, MAX_DIMENSION) ?: return bytes
        try {
            ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return bytes
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(bytes)
}
