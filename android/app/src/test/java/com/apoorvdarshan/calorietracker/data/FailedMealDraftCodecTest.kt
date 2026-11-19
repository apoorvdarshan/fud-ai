package com.apoorvdarshan.calorietracker.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FailedMealDraftCodecTest {
    @Test fun retainsOriginalInputsAndPhotoOrder() {
        val draft = FailedMealDraft("measure with this bowl", listOf(byteArrayOf(1, 2), byteArrayOf(3)), true, "123")
        val output = ByteArrayOutputStream()
        FailedMealDraftCodec.write(DataOutputStream(output), draft)
        val result = FailedMealDraftCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals(draft.text, result.text)
        assertEquals(draft.progressive, result.progressive)
        assertEquals(draft.labelBarcode, result.labelBarcode)
        assertArrayEquals(draft.images[0], result.images[0])
        assertArrayEquals(draft.images[1], result.images[1])
    }

    @Test fun refusesUnknownVersions() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 2)))
        assertThrows(IllegalStateException::class.java) { FailedMealDraftCodec.read(input) }
    }
}
