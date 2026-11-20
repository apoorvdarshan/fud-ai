package com.apoorvdarshan.calorietracker.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedMealDraftCodecTest {
    @Test fun retainsOriginalInputsAndPhotoOrder() {
        val draft = FailedMealDraft("measure with this bowl", listOf(byteArrayOf(1, 2), byteArrayOf(3)), true, "123")
        val result = roundTrip(draft)
        assertEquals(draft.text, result.text)
        assertEquals(draft.progressive, result.progressive)
        assertEquals(draft.labelBarcode, result.labelBarcode)
        assertArrayEquals(draft.images[0], result.images[0])
        assertArrayEquals(draft.images[1], result.images[1])
    }

    @Test fun longPastedRecipesRoundTrip() {
        // writeUTF threw above 65,535 encoded bytes; the v2 format carries 1 MiB.
        val longText = "Паста с рецептом ".repeat(20_000)
        assertTrue(longText.toByteArray(Charsets.UTF_8).size > 65_535)
        val result = roundTrip(FailedMealDraft(text = longText))
        assertEquals(longText, result.text)
    }

    @Test fun acceptedImportPhotoSizesAreSaveable() {
        // The library import accepts up to 25 MiB per photo; the draft must not
        // reject a photo the picker already accepted.
        val largePhoto = ByteArray(FailedMealDraftCodec.MAX_IMAGE_BYTES) { (it % 251).toByte() }
        val result = roundTrip(FailedMealDraft(images = listOf(largePhoto)))
        assertEquals(1, result.images.size)
        assertArrayEquals(largePhoto, result.images[0])
    }

    @Test fun rejectsPhotosAboveTheAcceptedImportCap() {
        val oversized = ByteArray(FailedMealDraftCodec.MAX_IMAGE_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(FailedMealDraft(images = listOf(oversized)))
        }
    }

    @Test fun rejectsTextAboveTheExplicitLimit() {
        val tooLong = "x".repeat(FailedMealDraftCodec.MAX_TEXT_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(FailedMealDraft(text = tooLong))
        }
    }

    @Test fun readsLegacyVersionOneDrafts() {
        // v1 used writeUTF for text and barcode; old saved drafts must still open.
        val output = ByteArrayOutputStream()
        with(DataOutputStream(output)) {
            writeInt(1)
            writeUTF("legacy note")
            writeUTF("4001234567890")
            writeBoolean(true)
            writeBoolean(false)
            writeInt(1)
            writeInt(2)
            write(byteArrayOf(9, 8))
        }
        val result = FailedMealDraftCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        assertEquals("legacy note", result.text)
        assertEquals("4001234567890", result.labelBarcode)
        assertTrue(result.progressive)
        assertArrayEquals(byteArrayOf(9, 8), result.images[0])
    }

    @Test fun refusesUnknownVersions() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(0, 0, 0, 99)))
        assertThrows(IllegalStateException::class.java) { FailedMealDraftCodec.read(input) }
    }

    private fun roundTrip(draft: FailedMealDraft): FailedMealDraft {
        val output = ByteArrayOutputStream()
        FailedMealDraftCodec.write(DataOutputStream(output), draft)
        return FailedMealDraftCodec.read(DataInputStream(ByteArrayInputStream(output.toByteArray())))
    }
}
