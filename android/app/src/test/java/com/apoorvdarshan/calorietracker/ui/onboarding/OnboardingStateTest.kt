package com.apoorvdarshan.calorietracker.ui.onboarding

import com.apoorvdarshan.calorietracker.models.AIProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {

    @Test
    fun providerChoiceBlocksContinue() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.CHOICE
        )
        assertFalse(state.canAdvance)
    }

    @Test
    fun byokWithoutKeyBlocksContinueForGemini() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = ""
        )
        assertFalse(state.canAdvance)
        assertFalse(state.byokSetupComplete)
    }

    @Test
    fun byokWithKeyEnablesContinue() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = "  test-key  "
        )
        assertTrue(state.byokSetupComplete)
        assertTrue(state.canAdvance)
    }

    @Test
    fun byokOllamaDoesNotRequireKey() {
        val state = OnboardingState(
            step = OnboardingStep.PROVIDER,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.OLLAMA,
            apiKey = ""
        )
        assertTrue(state.byokSetupComplete)
        assertTrue(state.canAdvance)
    }

    @Test
    fun providerStepReopensByokWhenConfigured() {
        val configured = OnboardingState(
            step = OnboardingStep.PLAN_READY,
            aiPhase = OnboardingAiPhase.BYOK,
            aiProvider = AIProvider.GEMINI,
            apiKey = "saved"
        )
        assertTrue(configured.aiPhaseForProviderStep() == OnboardingAiPhase.BYOK)

        val fresh = OnboardingState(step = OnboardingStep.PROVIDER)
        assertTrue(fresh.aiPhaseForProviderStep() == OnboardingAiPhase.CHOICE)
    }
}
