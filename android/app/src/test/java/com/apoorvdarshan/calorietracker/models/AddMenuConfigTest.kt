package com.apoorvdarshan.calorietracker.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddMenuConfigTest {
    @Test
    fun homeShortcutsDefaultSwapAndPersistWithoutDuplicates() {
        val shortcuts = HomeFoodShortcuts.decode(null)
        assertEquals(listOf(FoodLogMethod.CAMERA, FoodLogMethod.BARCODE, FoodLogMethod.TEXT, FoodLogMethod.FAVORITES), shortcuts.methods)
        val updated = shortcuts.assigning(0, FoodLogMethod.FAVORITES).assigning(1, FoodLogMethod.MANUAL)
        assertEquals(listOf(FoodLogMethod.FAVORITES, FoodLogMethod.MANUAL, FoodLogMethod.TEXT, FoodLogMethod.CAMERA),
            HomeFoodShortcuts.decode(updated.encode()).methods)
        assertEquals(shortcuts, HomeFoodShortcuts.decode("camera,camera,text,favorites"))
    }

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

    @Test
    fun restoreHiddenMethodAppendsToFlatLayout() {
        val config = AddMenuConfig(
            groups = emptyList(),
            flatMethods = listOf("camera", "text")
        )
        val restored = config.withRestoredMethod(FoodLogMethod.VOICE)
        assertTrue(restored.usesFlatLayout)
        assertEquals(
            listOf(FoodLogMethod.CAMERA, FoodLogMethod.TEXT, FoodLogMethod.VOICE),
            restored.resolvedFlatMethods()
        )
    }

    @Test
    fun restoreHiddenMethodAddsToLastGroup() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera")),
                AddMenuGroupConfig(id = "g2", name = "Type", methods = listOf("text"))
            )
        )
        val restored = config.withRestoredMethod(FoodLogMethod.VOICE)
        assertEquals(listOf("camera"), restored.groups[0].methods)
        assertEquals(listOf("text", "voice"), restored.groups[1].methods)
        assertEquals("g1", restored.groups[0].id)
        assertEquals("g2", restored.groups[1].id)
    }

    @Test
    fun restoreHiddenMethodAddsToFirstNonEmptyGroupWhenLastIsEmpty() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera")),
                AddMenuGroupConfig(id = "g2", name = "Empty", methods = emptyList())
            )
        )
        val restored = config.withRestoredMethod(FoodLogMethod.VOICE)
        assertEquals(listOf("camera", "voice"), restored.groups[0].methods)
        assertEquals("g1", restored.groups[0].id)
        // The editor keeps the newly added empty group until it is saved.
        assertEquals(listOf("g2"), restored.groups.drop(1).map { it.id })
        assertEquals(listOf("g1"), restored.sanitized().groups.map { it.id })
    }

    @Test
    fun restoreHiddenMethodToStandaloneNextToGroups() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera", "voice"))
            ),
            flatMethods = listOf("text")
        )
        val restored = config.withRestoredMethod(FoodLogMethod.VOICE, standalone = true)
        assertEquals(listOf("camera"), restored.groups.single().methods)
        assertEquals(listOf("text", "voice"), restored.flatMethods)
    }

    @Test
    fun groupsAndStandaloneMethodsRoundTrip() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera", "barcode"))
            ),
            flatMethods = listOf("voice", "camera", "text")
        )
        val decoded = AddMenuConfig.decode(AddMenuConfig.encode(config))
        assertEquals(AddMenuConfig.CURRENT_VERSION, decoded.version)
        assertEquals(listOf("camera", "barcode"), decoded.groups.single().methods)
        // A method already in a group is not repeated under +.
        assertEquals(listOf("voice", "text"), decoded.flatMethods)
        assertEquals(
            listOf(FoodLogMethod.CAMERA, FoodLogMethod.BARCODE, FoodLogMethod.VOICE, FoodLogMethod.TEXT),
            decoded.visibleMethods()
        )
    }

    @Test
    fun versionOneGroupedMenuStillIgnoresItsFlatList() {
        val raw = """
            {"version":1,"groups":[{"id":"g1","name":"Quick","methods":["camera"]}],"flatMethods":["voice"]}
        """.trimIndent()
        val config = AddMenuConfig.decode(raw)
        assertEquals(listOf("camera"), config.groups.single().methods)
        assertEquals(emptyList<String>(), config.flatMethods)
    }

    @Test
    fun versionOneFlatMenuDecodesUnchanged() {
        val raw = """
            {"version":1,"groups":[],"flatMethods":["voice","camera"]}
        """.trimIndent()
        val config = AddMenuConfig.decode(raw)
        assertTrue(config.groups.isEmpty())
        assertEquals(listOf("voice", "camera"), config.flatMethods)
    }

    @Test
    fun editorSanitizeKeepsEmptyGroupsAndTypedNames() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "My ", methods = listOf("camera", "camera")),
                AddMenuGroupConfig(id = "g2", name = "", methods = emptyList())
            )
        )
        val editing = config.sanitized(forEditor = true)
        assertEquals(listOf("g1", "g2"), editing.groups.map { it.id })
        assertEquals("My ", editing.groups[0].name)
        assertEquals(listOf("camera"), editing.groups[0].methods)
        assertEquals(AddMenuConfig.DEFAULT_GROUP_FALLBACK_NAME, editing.groups[1].name)

        val saved = config.sanitized()
        assertEquals(listOf("g1"), saved.groups.map { it.id })
        assertEquals("My", saved.groups.single().name)
    }

    @Test
    fun restoreHiddenMethodHonorsExplicitGroupIndex() {
        val config = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera")),
                AddMenuGroupConfig(id = "g2", name = "Type", methods = listOf("text"))
            )
        )
        val restored = config.withRestoredMethod(FoodLogMethod.VOICE, groupIndex = 0)
        assertEquals(listOf("camera", "voice"), restored.groups[0].methods)
        assertEquals(listOf("text"), restored.groups[1].methods)
    }

    @Test
    fun settingsSnapshotKeepsAnEditThatLandedWhileStartupWasReading() {
        val diskRead = AddMenuConfig.Default
        val edited = AddMenuConfig(
            groups = listOf(
                AddMenuGroupConfig(id = "g1", name = "Scan", methods = listOf("camera"))
            )
        )
        assertEquals(
            edited,
            AddMenuConfig.afterSettingsSnapshot(alreadyLoaded = true, onScreen = edited, diskRead = diskRead)
        )
        assertEquals(
            diskRead,
            AddMenuConfig.afterSettingsSnapshot(alreadyLoaded = false, onScreen = edited, diskRead = diskRead)
        )
    }
}
