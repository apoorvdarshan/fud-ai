package com.apoorvdarshan.calorietracker.ui.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class HeartRateFrameExtractionTest {
    @Test
    fun readsCameraXRgbaChannelsWithPaddedRows() {
        val width = 4
        val height = 4
        val pixelStride = 4
        val rowStride = 20
        val buffer = ByteBuffer.allocate(rowStride * height)
        val sampledOffset = rowStride + pixelStride
        buffer.put(sampledOffset, 200.toByte())
        buffer.put(sampledOffset + 1, 80.toByte())
        buffer.put(sampledOffset + 2, 40.toByte())
        buffer.put(sampledOffset + 3, 255.toByte())

        assertEquals(
            RgbMeans(
                red = 200.0,
                green = 80.0,
                blue = 40.0,
                redClippedFraction = 0.0,
                redSpatialStdDev = 0.0
            ),
            extractRgbaMeans(buffer, width, height, pixelStride, rowStride)
        )
    }

    @Test
    fun reportsMultiPixelSaturationAndSpatialVariance() {
        val width = 24
        val height = 24
        val pixelStride = 4
        val rowStride = width * pixelStride + 8
        val buffer = ByteBuffer.allocate(rowStride * height)
        val redValues = listOf(250, 255, 200, 100)
        var index = 0
        // Center-third ROI with step 4 samples (8,8), (12,8), (8,12), (12,12).
        for (y in 8 until 16 step 4) {
            for (x in 8 until 16 step 4) {
                val offset = y * rowStride + x * pixelStride
                buffer.put(offset, redValues[index++].toByte())
                buffer.put(offset + 1, 70.toByte())
                buffer.put(offset + 2, 45.toByte())
                buffer.put(offset + 3, 255.toByte())
            }
        }

        val means = requireNotNull(
            extractRgbaMeans(buffer, width, height, pixelStride, rowStride)
        )
        assertEquals(201.25, means.red, 0.001)
        assertEquals(70.0, means.green, 0.001)
        assertEquals(45.0, means.blue, 0.001)
        assertEquals(0.5, means.redClippedFraction, 0.001)
        assertEquals(62.287, means.redSpatialStdDev, 0.01)
    }

    @Test
    fun rejectsUnsupportedPackedPixelLayout() {
        assertNull(
            extractRgbaMeans(
                buffer = ByteBuffer.allocate(64),
                width = 4,
                height = 4,
                pixelStride = 3,
                rowStride = 16
            )
        )
    }
}
