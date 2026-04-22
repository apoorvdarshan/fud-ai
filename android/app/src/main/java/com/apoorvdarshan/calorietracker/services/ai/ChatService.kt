package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.services.WeightAnalysisService
import com.apoorvdarshan.calorietracker.services.WeightForecast
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Multi-turn coach chat. Builds a fresh system prompt every turn from live profile +
 * forecast + recent weights/foods, then routes to the right per-format client.
 * Port of iOS ChatService.
 */
class ChatService(
    private val prefs: PreferencesStore,
    private val keyStore: KeyStore,
    private val okHttp: OkHttpClient = FoodAnalysisService.defaultClient
) {

    suspend fun sendMessage(
        history: List<ChatMessage>,
        newUserMessage: String,
        profile: UserProfile,
        weights: List<WeightEntry>,
        foods: List<FoodEntry>,
        useMetric: Boolean
    ): String {
        val systemPrompt = buildSystemPrompt(profile, weights, foods, useMetric)

        val provider = prefs.selectedAIProvider.first()
        val model = prefs.selectedAIModel.first() ?: provider.defaultModel
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        val apiKey = keyStore.apiKey(provider)

        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)

        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI ->
                GeminiClient.chat(
                    okHttp, baseUrl, model, apiKey!!, systemPrompt,
                    history.map { roleFor(it) to it.content },
                    newUserMessage
                )
            AIProvider.ApiFormat.ANTHROPIC ->
                AnthropicClient.chat(
                    okHttp, baseUrl, model, apiKey!!, systemPrompt,
                    history.map { (if (it.role == ChatMessage.Role.USER) "user" else "assistant") to it.content },
                    newUserMessage
                )
            AIProvider.ApiFormat.OPENAI_COMPATIBLE ->
                OpenAICompatibleClient.chat(
                    okHttp, baseUrl, model, apiKey, systemPrompt,
                    history.map { (if (it.role == ChatMessage.Role.USER) "user" else "assistant") to it.content },
                    newUserMessage, provider
                )
        }
    }

    private fun roleFor(msg: ChatMessage): String =
        if (msg.role == ChatMessage.Role.USER) "user" else "model"

    private fun buildSystemPrompt(
        profile: UserProfile,
        weights: List<WeightEntry>,
        foods: List<FoodEntry>,
        useMetric: Boolean
    ): String {
        val forecast: WeightForecast = WeightAnalysisService.compute(weights, foods, profile)

        fun wUnit(kg: Double): String =
            if (useMetric) String.format(Locale.US, "%.1f kg", kg)
            else String.format(Locale.US, "%.1f lbs", kg * 2.20462)

        fun weekly(kg: Double): String =
            if (useMetric) String.format(Locale.US, "%+.2f kg/week", kg)
            else String.format(Locale.US, "%+.2f lbs/week", kg * 2.20462)

        val bmrFormula = if (profile.bodyFatPercentage != null)
            "Katch-McArdle (uses body fat %)"
        else "Mifflin-St Jeor (body fat not set)"

        val zone = ZoneId.systemDefault()
        val dateFmt = DateTimeFormatter.ofPattern("MMM d").withZone(zone)

        val recentWeights = weights.sortedByDescending { it.date }.take(10)
        val weightLog = recentWeights.reversed().joinToString(", ") {
            "${dateFmt.format(it.date)}: ${wUnit(it.weightKg)}"
        }

        val weekAgo = Instant.now().minusSeconds(7 * 86_400)
        val recentFoods = foods.filter { it.timestamp >= weekAgo }
        val dailyCal = sortedMapOf<String, Int>()
        for (entry in recentFoods) {
            val key = dateFmt.format(entry.timestamp)
            dailyCal[key] = (dailyCal[key] ?: 0) + entry.calories
        }
        val caloriesLog = dailyCal.entries.joinToString(", ") { "${it.key}: ${it.value} kcal" }

        val lines = mutableListOf<String>()
        lines.add("You are Coach, an AI nutrition and weight-change assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and always ground your recommendations in the user's own data below. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        lines.add("")
        lines.add("## User profile")
        lines.add("- Gender: ${profile.gender.name.lowercase()}")
        lines.add("- Age: ${profile.age}")
        val heightStr = if (useMetric) String.format(Locale.US, "%.0f cm", profile.heightCm)
        else String.format(Locale.US, "%.1f in", profile.heightCm / 2.54)
        lines.add("- Height: $heightStr")
        lines.add("- Current weight: ${wUnit(profile.weightKg)}")
        lines.add("- Activity: ${profile.activityLevel.displayName}")
        lines.add("- Goal: ${profile.goal.displayName}")
        profile.goalWeightKg?.let { lines.add("- Goal weight: ${wUnit(it)}") }
        profile.bodyFatPercentage?.let { lines.add("- Body fat: ${(it * 100).toInt()}%") }
        lines.add("")
        lines.add("## Formulas in use")
        lines.add("- BMR: $bmrFormula. Current BMR ≈ ${profile.bmr.toInt()} kcal/day")
        lines.add("- TDEE: BMR × activity multiplier ≈ ${profile.tdee.toInt()} kcal/day")
        lines.add("- Calorie goal: ${profile.effectiveCalories} kcal/day")
        lines.add("- Macro targets: ${profile.effectiveProtein}g protein, ${profile.effectiveCarbs}g carbs, ${profile.effectiveFat}g fat")
        lines.add("")
        lines.add("## Computed forecast (from their logged data)")
        if (forecast.hasEnoughData) {
            lines.add("- Days of food logged (last 90d): ${forecast.daysOfFoodData}")
            lines.add("- Weight entries available: ${forecast.weightEntriesUsed}")
            lines.add("- Avg daily intake: ${forecast.avgDailyCalories} kcal")
            val balanceSign = if (forecast.dailyEnergyBalance >= 0) "+" else ""
            lines.add("- Daily energy balance: ${balanceSign}${forecast.dailyEnergyBalance} kcal")
            lines.add("- Predicted change (from diet): ${weekly(forecast.predictedWeeklyChangeKg)}")
            forecast.observedWeeklyChangeKg?.let {
                lines.add("- Observed change (from scale): ${weekly(it)}")
            }
            lines.add("- Expected weight in 30 days: ${wUnit(forecast.predictedWeight30dKg)}")
            lines.add("- Expected weight in 60 days: ${wUnit(forecast.predictedWeight60dKg)}")
            lines.add("- Expected weight in 90 days: ${wUnit(forecast.predictedWeight90dKg)}")
            forecast.daysToGoal?.let { lines.add("- Days to goal at current pace: ~$it days") }
            if (forecast.trendsDisagree) {
                lines.add("- NOTE: Predicted and observed trends differ by >0.3 kg/week — user may be under-logging food.")
            }
        } else {
            lines.add("- Not enough data yet (need ≥2 days food + ≥2 weights). Encourage the user to log more.")
        }
        lines.add("")
        if (weightLog.isNotEmpty()) {
            lines.add("## Recent weights (oldest → newest)")
            lines.add(weightLog)
            lines.add("")
        }
        if (caloriesLog.isNotEmpty()) {
            lines.add("## Last 7 days of calorie totals")
            lines.add(caloriesLog)
            lines.add("")
        }
        lines.add("When the user asks how to lose or gain, give a concrete calorie target and at least one actionable food or activity change. When they ask expected weight, reference the forecast numbers above.")
        return lines.joinToString("\n")
    }

    @Suppress("unused")
    private fun Instant.toLocalDateInZone(): LocalDate = this.atZone(ZoneId.systemDefault()).toLocalDate()
}
