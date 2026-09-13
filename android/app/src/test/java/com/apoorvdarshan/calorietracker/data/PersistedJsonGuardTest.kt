package com.apoorvdarshan.calorietracker.data

import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FoodSource
import com.apoorvdarshan.calorietracker.models.MealType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class PersistedJsonGuardTest {
    // Same configuration as PreferencesStore.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val serializer = FoodEntry.serializer()

    private fun meal(name: String = "Rice and beans") = FoodEntry(
        name = name, calories = 270, protein = 12.0, carbs = 52.0, fat = 2.0,
        timestamp = Instant.parse("2027-01-15T08:00:00Z"),
        source = FoodSource.SNAP_FOOD, mealType = MealType.LUNCH
    )

    private fun encode(vararg entries: FoodEntry) =
        json.encodeToString(ListSerializer(serializer), entries.toList())

    @Test fun missingKeyIsMissingNotCorrupt() {
        val decoded = LenientJsonList.decode(json, serializer, null)
        assertEquals(PersistedListDecode.Missing, decoded)
        assertFalse(decoded.needsPreservation)
        assertTrue(decoded.itemsOrEmpty.isEmpty())
    }

    @Test fun validListDecodesWithoutPreservation() {
        val decoded = LenientJsonList.decode(json, serializer, encode(meal(), meal("Toast")))
        val result = decoded as PersistedListDecode.Decoded
        assertEquals(listOf("Rice and beans", "Toast"), result.items.map { it.name })
        assertEquals(0, result.dropped)
        assertFalse(decoded.needsPreservation)
    }

    @Test fun garbageIsCorruptAndKeepsRawForPreservation() {
        val raw = "[{\"id\": \"broken\""
        val decoded = LenientJsonList.decode(json, serializer, raw)
        assertEquals(PersistedListDecode.Corrupt(raw), decoded)
        assertTrue(decoded.needsPreservation)
        assertEquals(raw, decoded.rawOrNull)
        // The bug: this used to be indistinguishable from an empty diary.
        assertTrue(decoded.itemsOrEmpty.isEmpty())
    }

    @Test fun oneBadRowIsDroppedNotTheWholeList() {
        val good = meal()
        val goodJson = json.encodeToString(serializer, good)
        val raw = "[$goodJson, {\"name\": \"no id or macros\"}, $goodJson]"

        val decoded = LenientJsonList.decode(json, serializer, raw) as PersistedListDecode.Decoded
        assertEquals(listOf(good, good), decoded.items)
        assertEquals(1, decoded.dropped)
        assertTrue(decoded.needsPreservation)
        assertEquals(raw, decoded.rawOrNull)
    }

    @Test fun allRowsUnreadableIsCorrupt() {
        val raw = "[{\"name\": \"x\"}, {\"name\": \"y\"}]"
        assertEquals(PersistedListDecode.Corrupt(raw), LenientJsonList.decode(json, serializer, raw))
    }

    @Test fun unknownMealTypeCoercesToDefaultInsteadOfDroppingRow() {
        val raw = json.encodeToString(serializer, meal()).replace("\"lunch\"", "\"brunch\"")
        val decoded = LenientJsonList.decode(json, serializer, "[$raw]") as PersistedListDecode.Decoded
        assertEquals(MealType.OTHER, decoded.items.single().mealType)
        assertEquals(0, decoded.dropped)
    }

    @Test fun archivePreservesTextUnderCorruptSuffixAndNeverOverwrites() {
        val dir = java.nio.file.Files.createTempDirectory("guard").toFile()
        try {
            val archive = CorruptBlobArchive(dir)
            val first = archive.preserveText("foodEntries.json", "one")
            val second = archive.preserveText("foodEntries.json", "two")
            assertNotNull(first)
            assertNotNull(second)
            assertTrue(first!!.name.startsWith("foodEntries.json.corrupt-"))
            assertTrue(second!!.name.startsWith("foodEntries.json.corrupt-"))
            assertEquals("one", first.readText())
            assertEquals("two", second.readText())
            assertTrue(first.path != second.path)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun archiveReturnsNullWhenDirectoryCannotBeCreated() {
        val blocker = File.createTempFile("blocker", null)
        try {
            // A regular file where the directory should be: mkdirs fails.
            val archive = CorruptBlobArchive(File(blocker, "nested"))
            assertNull(archive.preserveText("foodEntries.json", "raw"))
        } finally {
            blocker.delete()
        }
    }

    @Test fun archiveCopiesCorruptDataStoreFileNextToOriginal() {
        val dir = java.nio.file.Files.createTempDirectory("guard").toFile()
        try {
            val source = File(dir, "fudai_prefs.preferences_pb").apply { writeText("not protobuf") }
            val backup = CorruptBlobArchive(File(dir, "unused"))
                .preserveFile(source)
            assertNotNull(backup)
            assertTrue(backup!!.name.startsWith("fudai_prefs.preferences_pb.corrupt-"))
            assertEquals("not protobuf", backup.readText())
            assertEquals("not protobuf", source.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
