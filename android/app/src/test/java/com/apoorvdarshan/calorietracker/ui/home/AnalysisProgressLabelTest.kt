package com.apoorvdarshan.calorietracker.ui.home

import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.services.ai.AnalysisKind
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressState
import com.apoorvdarshan.calorietracker.services.ai.AnalysisStage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks which stage copy the analyzing overlay shows (#635). The stages must stay
 * truthful: a barcode lookup is no AI call and an on-device model waits for nobody.
 */
class AnalysisProgressLabelTest {

    private fun state(
        kind: AnalysisKind = AnalysisKind.TEXT,
        stage: AnalysisStage = AnalysisStage.PREPARING,
        attempt: Int = 1,
        provider: AIProvider? = null,
    ) = AnalysisProgressState(
        kind = kind,
        stage = stage,
        attempt = attempt,
        provider = provider,
    )

    @Test
    fun aTextRequestIsPrepared() {
        assertEquals(R.string.analysis_stage_preparing, analysisStageLabelRes(state()))
    }

    @Test
    fun aPhotoRequestSaysThatThePhotoIsPrepared() {
        assertEquals(
            R.string.analysis_stage_preparing_image,
            analysisStageLabelRes(state(kind = AnalysisKind.IMAGE))
        )
    }

    @Test
    fun aBarcodeLookupNeverShowsAnAiStage() {
        assertEquals(
            R.string.analysis_stage_barcode,
            analysisStageLabelRes(state(kind = AnalysisKind.BARCODE))
        )
        assertEquals(
            R.string.analysis_stage_barcode,
            analysisStageLabelRes(state(kind = AnalysisKind.BARCODE, stage = AnalysisStage.REQUESTING))
        )
        assertEquals(
            R.string.analysis_stage_barcode,
            analysisStageLabelRes(state(kind = AnalysisKind.BARCODE, stage = AnalysisStage.RESPONDING))
        )
    }

    @Test
    fun aProviderRequestWaitsForTheProvider() {
        assertEquals(
            R.string.analysis_stage_requesting,
            analysisStageLabelRes(state(stage = AnalysisStage.REQUESTING, provider = AIProvider.OPENAI))
        )
    }

    @Test
    fun anOnDeviceModelDoesNotWaitForAProvider() {
        assertEquals(
            R.string.analysis_stage_on_device,
            analysisStageLabelRes(state(stage = AnalysisStage.REQUESTING, provider = AIProvider.LOCAL_GEMMA))
        )
    }

    @Test
    fun theResponseAndTheCheckHaveTheirOwnStages() {
        assertEquals(
            R.string.analysis_stage_responding,
            analysisStageLabelRes(state(stage = AnalysisStage.RESPONDING))
        )
        assertEquals(
            R.string.analysis_stage_validating,
            analysisStageLabelRes(state(stage = AnalysisStage.VALIDATING))
        )
    }

    @Test
    fun theBackupModelIsCalledAttemptTwo() {
        assertEquals(
            R.string.analysis_stage_fallback_attempt,
            analysisStageLabelRes(state(stage = AnalysisStage.FALLBACK, attempt = 2))
        )
        // The attempt is always named, so the user knows a second try is running.
        assertEquals(
            R.string.analysis_stage_fallback_attempt,
            analysisStageLabelRes(state(stage = AnalysisStage.FALLBACK, attempt = 1))
        )
    }
}
