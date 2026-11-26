package com.apoorvdarshan.calorietracker.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetGaugeSizingTest {
    @Test
    fun defaultGaugeUsesTheClassicPinkGradient() {
        assertEquals(0xFF375F, DEFAULT_THEME_START)
        assertEquals(0xFF6B8A, DEFAULT_THEME_END)
    }

    @Test
    fun shortValuesKeepTheNormalGaugeFontSize() {
        assertEquals(34, gaugeCenterFontSizeSp(gaugeWidthDp = 210, text = "1500"))
        assertEquals(21, gaugeCenterFontSizeSp(gaugeWidthDp = 112, text = "84g"))
    }

    @Test
    fun longValuesShrinkToClearTheSemicircle() {
        val shortSize = gaugeCenterFontSizeSp(gaugeWidthDp = 210, text = "1500")
        val longSize = gaugeCenterFontSizeSp(gaugeWidthDp = 210, text = "1500 ml")

        assertEquals(24, longSize)
        assertTrue(longSize < shortSize)
        assertEquals(14, gaugeCenterFontSizeSp(gaugeWidthDp = 112, text = "1234mg"))
    }

    @Test
    fun goalTextAlsoRespectsAvailableWidth() {
        assertEquals(11, gaugeSecondaryFontSizeSp(gaugeWidthDp = 112, text = "/ 2000 ml"))
    }
}
