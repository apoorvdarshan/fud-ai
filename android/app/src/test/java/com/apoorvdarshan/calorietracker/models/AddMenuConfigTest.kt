package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMenuConfigTest {
    @Test
    fun defaultMatchesLegacyThreeGroups() {
        val config = AddMenuConfig.Default
        assertEquals(3, config.groups.size)
        assertEquals(AddMenuConfig.DEFAULT_GROUP_PHOTO_SCAN, config.groups[0].name)
        assertEquals(
            listOf("camera", "photos", "barcode"),
            config.groups[0].methods
        )
        assertEquals(AddMenuConfig.DEFAULT_GROUP_DESCRIBE_MEAL, config.groups[1].name)
        assertEquals(
            listOf("text", "voice", "manual"),
            config.groups[1].methods
        )
        assertEquals(AddMenuConfig.DEFAULT_GROUP_REUSE_MEAL, config.groups[2].name)
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

    @Test
    fun sanitizeDropsEmptyGroups() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(name = "Empty", methods = emptyList()),
                AddMenuGroupConfig(name = "Quick", methods = listOf("camera"))
            )
        ).sanitized()
        assertEquals(1, config.groups.size)
        assertEquals(listOf("camera"), config.groups.single().methods)
    }

    @Test
    fun decodeFallsBackToDefaultWhenAllMethodsUnknown() {
        val raw = """
            {"version":1,"groups":[{"id":"g1","name":"Quick","methods":["unknown_method"]}],"flatMethods":[]}
        """.trimIndent()
        val config = AddMenuConfig.decode(raw)
        assertEquals(AddMenuConfig.Default, config)
    }

    @Test
    fun decodeIgnoresUnknownMethodsButKeepsKnownOnes() {
        val raw = """
            {"version":1,"groups":[{"id":"g1","name":"Quick","methods":["camera","unknown_method","voice"]}],"flatMethods":[]}
        """.trimIndent()
        val config = AddMenuConfig.decode(raw)
        assertEquals(listOf("camera", "voice"), config.groups.single().methods)
    }
}
