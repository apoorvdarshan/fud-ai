package com.apoorvdarshan.calorietracker.models

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.services.voice.RepoFiles
import com.apoorvdarshan.calorietracker.ui.workouts.importedWorkoutDistanceValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/** Health Connect workout import (#479) ships in English plus all 18 app languages. */
class ImportedHealthWorkoutStringsTest {
    private val languageFolders = listOf(
        "values-ar", "values-az", "values-cs", "values-de", "values-es", "values-fr", "values-hi",
        "values-it", "values-ja", "values-ko", "values-nl", "values-pl", "values-pt-rBR", "values-ro",
        "values-ru", "values-tr", "values-uk", "values-zh-rCN"
    )

    private fun strings(file: File): Map<String, String> =
        Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun plurals(file: File): Map<String, String> =
        Regex("<item quantity=\"([a-z]+)\">(.*?)</item>")
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun placeholders(text: String): List<String> =
        Regex("%\\d\\$[sd]").findAll(text).map { it.value }.toList()

    @Test
    fun everyLanguageTranslatesEveryStringWithTheSamePlaceholders() {
        val res = RepoFiles.find("src/main/res")
        val english = strings(File(res, "values/health_workout_import.xml")) +
            ("settings_health_exercise_access" to strings(File(res, "values/strings.xml"))
                .getValue("settings_health_exercise_access"))
        for (folder in languageFolders) {
            val translated = strings(File(res, "$folder/health_workout_import.xml")) +
                ("settings_health_exercise_access" to strings(File(res, "$folder/strings.xml"))
                    .getValue("settings_health_exercise_access"))
            assertEquals("$folder keys", english.keys, translated.keys)
            for ((key, text) in english) {
                val value = translated.getValue(key)
                assertTrue("$folder $key is empty", value.isNotBlank())
                assertEquals("$folder $key placeholders", placeholders(text), placeholders(value))
                // An unescaped apostrophe or quote breaks or silently changes Android strings.
                assertTrue("$folder $key: $value", !Regex("(?<!\\\\)['\"]").containsMatchIn(value))
            }
            val counts = plurals(File(res, "$folder/health_workout_import.xml"))
            assertTrue("$folder plural other", "other" in counts)
            counts.values.forEach { assertEquals("$folder plural", listOf("%1\$d"), placeholders(it)) }
        }
    }

    @Test
    fun everyHealthConnectExerciseTypeHasItsOwnName() {
        val types = ExerciseSessionRecord::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("EXERCISE_TYPE_") && it.type == Int::class.javaPrimitiveType }
            .associate { it.name to it.getInt(null) }
        assertTrue(types.size > 50)
        for ((name, type) in types) {
            if (type == ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT) continue
            assertNotEquals(name, R.string.health_exercise_workout, importedWorkoutTypeLabel(type))
        }
        assertEquals(R.string.health_exercise_workout, importedWorkoutTypeLabel(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
        assertEquals(R.string.health_exercise_workout, importedWorkoutTypeLabel(9_999))
        assertEquals(R.string.health_exercise_running, importedWorkoutTypeLabel(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
    }

    @Test
    fun distanceFollowsTheLengthUnit() {
        assertEquals(7.52, importedWorkoutDistanceValue(7_520.0, metric = true), 1e-9)
        assertEquals(1.0, importedWorkoutDistanceValue(1_609.344, metric = false), 1e-9)
    }
}
