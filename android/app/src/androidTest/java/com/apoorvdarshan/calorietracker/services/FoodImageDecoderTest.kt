package com.apoorvdarshan.calorietracker.services

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
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
        withPhoto(null) { file ->
            val original = file.readBytes()
            val upload = FoodImagePreprocessor.prepareForUpload(original)
            assertTrue(upload.isNotEmpty())
            assertFalse(upload.contentEquals(original))
            val decoded = FoodImageDecoder.decode(upload)!!
            assertTrue(maxOf(decoded.width, decoded.height) <= 1_600)
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
        val invalid = byteArrayOf(1, 2, 3)
        assertArrayEquals(invalid, FoodImagePreprocessor.prepareForUpload(invalid))
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

    private fun withPhoto(orientation: Int?, block: (File) -> Unit) {
        val file = File.createTempFile("orientation-", ".jpg", context.cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            for (y in 0 until 80) for (x in 0 until 120) {
                bitmap.setPixel(x, y, colors[(if (y >= 40) 2 else 0) + (if (x >= 60) 1 else 0)])
            }
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
