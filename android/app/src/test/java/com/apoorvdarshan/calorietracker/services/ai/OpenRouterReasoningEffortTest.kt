package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.models.OpenRouterReasoningEffort
import org.junit.Assert.*
import org.junit.Test

class OpenRouterReasoningEffortTest {
    @Test fun autoPreservesAnalysisAndCoachDefaults() {
        assertEquals(mapOf("exclude" to true), OpenRouterReasoningEffort.AUTO.requestOptions(false, true))
        assertNull(OpenRouterReasoningEffort.AUTO.requestOptions(false, false))
        assertEquals(OpenRouterReasoningEffort.AUTO, OpenRouterReasoningEffort.fromValue(null))
        assertEquals(OpenRouterReasoningEffort.AUTO, OpenRouterReasoningEffort.fromValue("invalid"))
    }

    @Test fun explicitLevelsRoundTripAndApplyToBothRoutes() {
        OpenRouterReasoningEffort.entries.filter { it != OpenRouterReasoningEffort.AUTO }.forEach { effort ->
            assertEquals(effort, OpenRouterReasoningEffort.fromValue(effort.value))
            assertEquals(mapOf("effort" to effort.value), effort.requestOptions(false, false))
            assertEquals(mapOf("effort" to effort.value, "exclude" to true), effort.requestOptions(false, true))
        }
    }

    @Test fun retriesAlwaysPreserveLowEffortRecovery() {
        OpenRouterReasoningEffort.entries.forEach { effort ->
            for (exclude in listOf(false, true)) {
                assertEquals(mapOf("effort" to "low", "exclude" to true), effort.requestOptions(true, exclude))
            }
        }
    }
}
