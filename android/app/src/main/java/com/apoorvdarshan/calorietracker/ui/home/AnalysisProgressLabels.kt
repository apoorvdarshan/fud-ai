package com.apoorvdarshan.calorietracker.ui.home

import androidx.annotation.StringRes
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.services.ai.AnalysisKind
import com.apoorvdarshan.calorietracker.services.ai.AnalysisProgressState
import com.apoorvdarshan.calorietracker.services.ai.AnalysisStage

/**
 * The string resource of the stage an analysis is in.
 *
 * The stages only describe work that really happened: a barcode lookup is no AI call,
 * so it never shows an AI stage, and an on-device model does not wait for a provider.
 *
 * Kept free of Compose so the stage choice can be checked by a plain unit test.
 */
@StringRes
fun analysisStageLabelRes(progress: AnalysisProgressState): Int = when {
    // A barcode lookup is a database request, not an AI call.
    progress.kind == AnalysisKind.BARCODE -> R.string.analysis_stage_barcode
    progress.stage == AnalysisStage.PREPARING && progress.kind == AnalysisKind.IMAGE ->
        R.string.analysis_stage_preparing_image
    progress.stage == AnalysisStage.PREPARING -> R.string.analysis_stage_preparing
    // The on-device model runs here, it does not wait for a provider.
    progress.stage == AnalysisStage.REQUESTING && progress.provider == AIProvider.LOCAL_GEMMA ->
        R.string.analysis_stage_on_device
    progress.stage == AnalysisStage.REQUESTING -> R.string.analysis_stage_requesting
    progress.stage == AnalysisStage.RESPONDING -> R.string.analysis_stage_responding
    progress.stage == AnalysisStage.VALIDATING -> R.string.analysis_stage_validating
    // The backup model is always the second try, and the attempt is named.
    progress.stage == AnalysisStage.FALLBACK -> R.string.analysis_stage_fallback_attempt
    else -> R.string.analysis_stage_preparing
}
