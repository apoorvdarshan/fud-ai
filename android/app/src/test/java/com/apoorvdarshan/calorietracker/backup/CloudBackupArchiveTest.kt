package com.apoorvdarshan.calorietracker.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CloudBackupArchiveTest {
    @Test
    fun roundTripKeepsFoodEntryIdsAndPhotos() {
        val id = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val values = mapOf(
            "foodEntries" to CloudBackupValue.string("""[{"id":"$id","name":"Oats"}]"""),
            "healthChangesToken" to CloudBackupValue.string("device-token"),
            "healthFoodRestoreDone" to CloudBackupValue.bool(false),
        )
        val photo = byteArrayOf(1, 2, 3, 4)
        val zip = CloudBackupArchive.pack(
            values = values,
            photos = mapOf("$id.jpg" to photo),
            exportedAt = "2026-09-10T12:00:00Z",
            appVersion = "7.0",
        )
        val unpack = CloudBackupArchive.unpack(zip)
        assertEquals(CloudBackupPolicy.FORMAT, unpack.document.format)
        assertEquals(1, unpack.document.format_version)
        assertEquals("""[{"id":"$id","name":"Oats"}]""", unpack.document.payload.values.getValue("foodEntries").s)
        assertFalse(unpack.document.payload.values.containsKey("healthChangesToken"))
        assertFalse(unpack.document.payload.values.containsKey("healthFoodRestoreDone"))
        assertTrue(unpack.photos.containsKey("$id.jpg"))
        assertEquals(photo.toList(), unpack.photos.getValue("$id.jpg").toList())
    }

    @Test
    fun newerFormatVersionFailsClosed() {
        val unpack = CloudBackupArchive.unpack(
            CloudBackupArchive.pack(
                values = mapOf("useMetric" to CloudBackupValue.bool(true)),
                photos = emptyMap(),
                exportedAt = "2026-09-10T12:00:00Z",
                appVersion = "7.0",
            )
        )
        val newer = unpack.document.copy(format_version = CloudBackupPolicy.VERSION + 1)
        val encoded = kotlinx.serialization.json.Json.encodeToString(
            CloudBackupDocument.serializer(),
            newer,
        ).toByteArray()
        val rawOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(rawOut).use { zipOut ->
            zipOut.putNextEntry(java.util.zip.ZipEntry(CloudBackupPolicy.PAYLOAD_NAME))
            zipOut.write(encoded)
            zipOut.closeEntry()
        }
        val error = runCatching { CloudBackupArchive.unpack(rawOut.toByteArray()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("newer"))
    }

    @Test
    fun unchangedHashSkipsWork() {
        val values = mapOf("foodEntries" to CloudBackupValue.string("[]"))
        val hash1 = CloudBackupArchive.contentHash(values, emptyMap())
        val hash2 = CloudBackupArchive.contentHash(values, emptyMap())
        assertEquals(hash1, hash2)
        val hash3 = CloudBackupArchive.contentHash(
            mapOf("foodEntries" to CloudBackupValue.string("[1]")),
            emptyMap(),
        )
        assertTrue(hash1 != hash3)
    }

    @Test
    fun rejectsPathTraversalPhotoNames() {
        assertEquals("secret.jpg", CloudBackupPolicy.safePhotoName("../secret.jpg"))
        assertEquals("meal.jpg", CloudBackupPolicy.safePhotoName("photos/meal.jpg"))
        assertEquals(null, CloudBackupPolicy.safePhotoName("notes.txt"))
    }
}
