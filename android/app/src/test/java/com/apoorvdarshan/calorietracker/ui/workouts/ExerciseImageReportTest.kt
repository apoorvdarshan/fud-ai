package com.apoorvdarshan.calorietracker.ui.workouts

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseImageReportTest {
    @Test
    fun reportCarriesCatalogMetadataAndEscapesTheUsersNote() {
        val note = "Frame 2: हाथ + leg & angle\n@person"
        val uri = URI(ExerciseImageReport.issueUrl("Lunge", "Lunge", "Lunge_female_v2_0.png", "7.0", note))
        val query = uri.rawQuery.split("&").associate {
            val (key, encoded) = it.split("=", limit = 2)
            key to URLDecoder.decode(encoded, "UTF-8")
        }
        assertEquals("github.com", uri.host)
        assertEquals("/apoorvdarshan/fud-ai/issues/new", uri.path)
        assertEquals("exercise_visual.md", query["template"])
        assertTrue(query.getValue("body").contains("    Frame 2: हाथ + leg & angle\n    @person"))
        assertTrue(query.getValue("body").contains("Lunge_female_v2_0.png"))
        assertTrue(ExerciseImageReport.text("Lunge", "Lunge", "image.png", "7.0", note).endsWith(note))
    }
}
