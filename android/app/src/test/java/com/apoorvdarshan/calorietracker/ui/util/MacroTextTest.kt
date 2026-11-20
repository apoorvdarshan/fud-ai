package com.apoorvdarshan.calorietracker.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroTextTest {
    @Test
    fun unitWithItsOwnSpaceKeepsItWhenTheFormatAddsNone() {
        // English: macro_value_unit_format is "%1$s%2$s", so " fl oz" supplies the space itself.
        assertEquals("64 fl oz", joinValueAndUnit("64", " fl oz", "64fl oz"))
        assertEquals("250ml", joinValueAndUnit("250", "ml", "250ml"))
    }

    @Test
    fun unitWithItsOwnSpaceDoesNotDoubleTheFormatsSpace() {
        // Czech: the format is "%1$s %2$s", so the unit's leading space is dropped.
        assertEquals("64 fl oz", joinValueAndUnit("64", " fl oz", "64 fl oz"))
        assertEquals("250 ml", joinValueAndUnit("250", "ml", "250 ml"))
    }
}
