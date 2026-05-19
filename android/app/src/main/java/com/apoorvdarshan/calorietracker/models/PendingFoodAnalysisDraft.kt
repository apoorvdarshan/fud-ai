package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.services.ai.FoodAnalysis
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class PendingFoodAnalysisDraft(
    val analysis: FoodAnalysis,
    val imageFilename: String? = null,
    val source: FoodSource? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now()
)
