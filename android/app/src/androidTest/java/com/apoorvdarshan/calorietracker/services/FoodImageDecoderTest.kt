package com.apoorvdarshan.calorietracker.services

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.apoorvdarshan.calorietracker.services.ai.AiError
import com.apoorvdarshan.calorietracker.services.ai.AiErrorKind
import com.apoorvdarshan.calorietracker.services.ai.FoodImagePreprocessor
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android bitmap/EXIF operations, with asymmetric pixels to detect flips as well as rotation. */
class FoodImageDecoderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
    private val expectedCorners = listOf(
        listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(3, 2, 1, 0), listOf(2, 3, 0, 1),
        listOf(0, 2, 1, 3), listOf(2, 0, 3, 1), listOf(3, 1, 2, 0), listOf(1, 3, 0, 2)
    )

    @Test fun allExifOrientationsWorkForFilesBytesAndUploads() {
        for (orientation in 1..8) withPhoto(orientation) { file ->
            val original = file.readBytes()
            for (bitmap in listOf(
                FoodImageDecoder.decode(file),
                FoodImageDecoder.decode(original),
                FoodImageDecoder.decode(original, 40),
                FoodImageDecoder.decode(FoodImagePreprocessor.prepareForUpload(original))
            )) {
                assertNotNull("orientation $orientation", bitmap)
                bitmap!!
                assertEquals(orientation >= 5, bitmap.height > bitmap.width)
                assertCorners(bitmap, expectedCorners[orientation - 1])
                bitmap.recycle()
            }
            val sampled = FoodImageDecoder.decode(original, 40)!!
            assertEquals(40, maxOf(sampled.width, sampled.height))
            sampled.recycle()
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun uploadPreprocessorCapsDimensionsWithoutChangingOriginal() {
        withPhoto(null, width = 2_400, height = 1_800) { file ->
            val original = file.readBytes()
            val upload = FoodImagePreprocessor.prepareForUpload(original)
            assertTrue(upload.isNotEmpty())
            assertFalse(upload.contentEquals(original))
            val decoded = FoodImageDecoder.decode(upload)!!
            assertEquals(1_600, maxOf(decoded.width, decoded.height))
            decoded.recycle()
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun untaggedPhotosAndInvalidInputsAreSafe() {
        withPhoto(null) { file ->
            val bitmap = FoodImageDecoder.decode(file)!!
            assertCorners(bitmap, expectedCorners.first())
            bitmap.recycle()
        }
        assertNull(FoodImageDecoder.decode(byteArrayOf()))
        assertNull(FoodImageDecoder.decode(byteArrayOf(1, 2, 3)))
        assertNull(FoodImageDecoder.decode(File(context.cacheDir, "missing-${UUID.randomUUID()}")))
        // Undecodable bytes must never be uploaded labeled as image/jpeg.
        for (invalid in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), "not an image".toByteArray())) {
            val error = assertThrows(AiError::class.java) { FoodImagePreprocessor.prepareForUpload(invalid) }
            assertEquals(AiErrorKind.IMAGE_CONVERSION, error.kind)
        }
    }

    @Test fun uploadPreprocessorAlwaysProducesJpegFromOtherFormats() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP)) {
            val encoded = encodedPhoto(format, width = 2_000, height = 1_000)
            assertFalse("$format input is not a JPEG", encoded.isJpeg())
            val upload = FoodImagePreprocessor.prepareForUpload(encoded)
            assertTrue("$format upload is a JPEG", upload.isJpeg())
            val decoded = FoodImageDecoder.decode(upload)!!
            assertEquals(1_600, maxOf(decoded.width, decoded.height))
            assertCorners(decoded, expectedCorners.first())
            decoded.recycle()
        }
        val fromJpeg = FoodImagePreprocessor.prepareForUpload(encodedPhoto(Bitmap.CompressFormat.JPEG))
        assertTrue(fromJpeg.isJpeg())
    }

    @Test fun imageDecoderFallbackMatchesBitmapFactoryForEveryOrientation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        for (orientation in 1..8) withPhoto(orientation, width = 240, height = 160) { file ->
            val original = file.readBytes()
            val fallback = FoodImageDecoder.decodeWithImageDecoder(original)
            assertNotNull("orientation $orientation", fallback)
            fallback!!
            assertEquals(orientation >= 5, fallback.height > fallback.width)
            assertCorners(fallback, expectedCorners[orientation - 1])
            fallback.recycle()

            val capped = FoodImageDecoder.decodeWithImageDecoder(original, 40)!!
            assertEquals(40, maxOf(capped.width, capped.height))
            assertCorners(capped, expectedCorners[orientation - 1])
            capped.recycle()
        }
        assertNull(FoodImageDecoder.decodeWithImageDecoder(byteArrayOf(1, 2, 3)))
        val webp = FoodImageDecoder.decodeWithImageDecoder(encodedPhoto(Bitmap.CompressFormat.WEBP), 60)!!
        assertEquals(60, maxOf(webp.width, webp.height))
        assertCorners(webp, expectedCorners.first())
        webp.recycle()
    }

    private fun ByteArray.isJpeg(): Boolean =
        size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

    private fun encodedPhoto(format: Bitmap.CompressFormat, width: Int = 120, height: Int = 80): ByteArray {
        val bitmap = quadrantBitmap(width, height)
        return try {
            java.io.ByteArrayOutputStream().use { out ->
                assertTrue(bitmap.compress(format, 100, out))
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun quadrantBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) {
            bitmap.setPixel(x, y, colors[(if (y >= height / 2) 2 else 0) + (if (x >= width / 2) 1 else 0)])
        }
        return bitmap
    }

    @Test fun legacyThumbnailsHealWithoutChangingOriginalsOrOtherData() {
        val root = File(context.cacheDir, "orientation-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root
        }
        try {
            withPhoto(6) { photo ->
                val original = photo.readBytes()
                val originals = File(root, "fudai-food-images").apply { mkdirs() }
                val filename = "existing.jpg"
                File(originals, filename).writeBytes(original)
                val legacy = File(root, "fudai-food-thumbnails").apply { mkdirs() }
                val sideways = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size)
                File(legacy, filename).outputStream().use { sideways.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                sideways.recycle()
                val sentinel = File(root, "meal-data").apply { writeText("preserve") }
                val store = FoodImageStore(isolatedContext)
                assertFalse(legacy.exists())
                assertCorners(store.load(filename)!!, expectedCorners[5])
                assertCorners(store.loadThumbnail(filename)!!, expectedCorners[5])
                assertTrue(File(root, "fudai-food-thumbnails-v2/$filename").exists())
                // A new instance exercises the on-disk cache, rather than just memory.
                assertCorners(FoodImageStore(isolatedContext).loadThumbnail(filename)!!, expectedCorners[5])
                val newName = store.storeBytes(original, UUID.randomUUID())!!
                assertCorners(store.loadThumbnail(newName)!!, expectedCorners[5])
                assertArrayEquals(original, File(originals, filename).readBytes())
                assertArrayEquals(original, store.file(newName).readBytes())
                assertEquals("preserve", sentinel.readText())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withPhoto(
        orientation: Int?,
        width: Int = 120,
        height: Int = 80,
        block: (File) -> Unit
    ) {
        val file = File.createTempFile("orientation-", ".jpg", context.cacheDir)
        try {
            val bitmap = quadrantBitmap(width, height)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bitmap.recycle()
            if (orientation != null) ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            block(file)
        } finally {
            file.delete()
        }
    }

    private fun assertCorners(bitmap: Bitmap, expected: List<Int>) {
        val positions = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3)
        val actual = positions.map { (x, y) ->
            val pixel = bitmap.getPixel(bitmap.width * x / 4, bitmap.height * y / 4)
            colors.indices.minBy { index ->
                val color = colors[index]
                listOf(Color.red(pixel) - Color.red(color), Color.green(pixel) - Color.green(color),
                    Color.blue(pixel) - Color.blue(color)).sumOf { it * it }
            }
        }
        assertEquals(expected, actual)
    }
}
