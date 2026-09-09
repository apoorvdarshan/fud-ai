package com.apoorvdarshan.calorietracker.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max

/** Decodes upright pixels without modifying the original photo or its metadata. */
internal object FoodImageDecoder {
    fun decode(bytes: ByteArray, maxDimension: Int = Int.MAX_VALUE): Bitmap? = decode(
        maxDimension,
        { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) },
        { ByteArrayInputStream(bytes).use { ExifInterface(it).orientation() } }
    )

    fun decode(file: File, maxDimension: Int = Int.MAX_VALUE): Bitmap? = decode(
        maxDimension,
        { options -> BitmapFactory.decodeFile(file.absolutePath, options) },
        { ExifInterface(file.absolutePath).orientation() }
    )

    private fun decode(
        maxDimension: Int,
        readBitmap: (BitmapFactory.Options) -> Bitmap?,
        readOrientation: () -> Int
    ): Bitmap? {
        require(maxDimension > 0)
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            readBitmap(bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sampleSize / 2 >= maxDimension) sampleSize *= 2
            val decoded = readBitmap(BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null
            val orientation = runCatching(readOrientation).getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val oriented = decoded.applyingExifOrientation(orientation)
            if (oriented !== decoded) decoded.recycle()
            val scaled = oriented.scaledToMaxDimension(maxDimension)
            if (scaled !== oriented) oriented.recycle()
            scaled
        }.getOrNull()
    }

    private fun ExifInterface.orientation(): Int =
        getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    fun Bitmap.scaledToMaxDimension(maxDimension: Int): Bitmap {
        require(maxDimension > 0)
        val longest = max(width, height)
        if (longest <= maxDimension) return this
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun Bitmap.applyingExifOrientation(orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}
