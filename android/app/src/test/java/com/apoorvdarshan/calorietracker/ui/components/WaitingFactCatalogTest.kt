package com.apoorvdarshan.calorietracker.ui.components

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WaitingFactCatalogTest {
    @Test
    fun bundledFactsHaveConfirmedAmountsAndCoverEveryAppLanguage() {
        val asset = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { it.resolve("ios/calorietracker/Resources/WaitingFactsData/analysis_nutrition_facts.json") }
            .first { it.isFile }
        val catalog = WaitingFactCatalog.decode(asset.readText())
        assertEquals(140, catalog.foods.size)
        for (language in "en ar az cs de es fr hi it ja ko nl pl pt-BR ro ru tr uk zh-Hans".split(" ")) {
            assertEquals(1965, catalog.measurements(language).size)
        }
        val banana = catalog.foods.single { it.id == 173944 }
        assertEquals(358.0, banana.values["potassium"])
        assertEquals("केला", banana.names["hi"])
        assertEquals("zh-Hans", WaitingFactCatalog.language(Locale.SIMPLIFIED_CHINESE))
        assertEquals("en", WaitingFactCatalog.language(Locale.CANADA))
    }

    @Test
    fun aDeckShowsEachFactOnceAndCanResumeWithTheSameSeed() {
        val order = WaitingFactDeck.order(1965, 1234L)
        assertEquals(1970, order.size)
        assertEquals((0 until 1970).toSet(), order.toSet())
        assertEquals(order, WaitingFactDeck.order(1965, 1234L))
        assertNotEquals(order, WaitingFactDeck.order(1965, 5L))
        assertEquals((0 until 5).toSet(), WaitingFactDeck.order(0, 1234L).toSet())
    }
}
