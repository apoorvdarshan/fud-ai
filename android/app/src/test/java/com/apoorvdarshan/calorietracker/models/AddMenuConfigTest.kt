package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMenuConfigTest {
    @Test
    fun defaultMatchesLegacyThreeGroups() {
        val config = AddMenuConfig.Default
        assertEquals(3, config.groups.size)
        assertEquals(
            listOf("camera", "photos", "barcode"),
            config.groups[0].methods
        )
        assertEquals(
            listOf("text", "voice", "manual"),
            config.groups[1].methods
        )
        assertEquals(
            listOf("recent", "frequent", "favorites", "copy_from_day"),
            config.groups[2].methods
        )
    }

    @Test
    fun decodeSanitizesDuplicateMethods() {
        val raw = """
            {"version":1,"groups":[{"id":"g1","name":"Quick","methods":["camera","camera","voice"]}],"flatMethods":[]}
        """.trimIndent()
        val config = AddMenuConfig.decode(raw)
        assertEquals(listOf("camera", "voice"), config.groups.single().methods)
    }

    @Test
    fun flatLayoutUsesFlatMethods() {
        val config = AddMenuConfig(
            groups = emptyList(),
            flatMethods = listOf("camera", "text", "voice")
        ).sanitized()
        assertTrue(config.usesFlatLayout)
        assertEquals(
            listOf(FoodLogMethod.CAMERA, FoodLogMethod.TEXT, FoodLogMethod.VOICE),
            config.resolvedFlatMethods()
        )
    }
}
