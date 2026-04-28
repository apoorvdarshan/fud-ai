package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.KeyStore
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.ActivityLevel
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.WeightGoal
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.services.WeightAnalysisService
import com.apoorvdarshan.calorietracker.services.WeightForecast
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Multi-turn coach chat with **tool calling** — Coach can fetch any slice of
 * the user's data on demand instead of the previous fixed last-N-entries dump
 * in the system prompt. Tool definitions per provider are built inline below
 * (Gemini functionDeclarations / Anthropic input_schema / OpenAI function);
 * the executor side lives in [CoachTools].
 *
 * Per-format multi-turn loops are capped at MAX_TOOL_ROUNDS to bound any
 * runaway recursion from a misbehaving model.
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
        bodyFats: List<BodyFatEntry>,
        foods: List<FoodEntry>,
        useMetric: Boolean
    ): String {
        val baseSystemPrompt = buildSystemPrompt(profile, weights, bodyFats, foods, useMetric)
        val userContext = prefs.userContext.first()
        val systemPrompt = if (userContext.isNotBlank())
            "$baseSystemPrompt\n\n## User-provided context\n$userContext"
        else baseSystemPrompt
        val tools = CoachTools(weights = weights, bodyFats = bodyFats, foods = foods)

        val provider = prefs.selectedAIProvider.first()
        val model = prefs.selectedAIModel.first() ?: provider.defaultModel
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        val apiKey = keyStore.apiKey(provider)

        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)

        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI -> runGeminiToolLoop(baseUrl, model, apiKey!!, systemPrompt, history, newUserMessage, tools)
            AIProvider.ApiFormat.ANTHROPIC -> runAnthropicToolLoop(baseUrl, model, apiKey!!, systemPrompt, history, newUserMessage, tools)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE -> runOpenAIToolLoop(baseUrl, model, apiKey, systemPrompt, history, newUserMessage, provider, tools)
        }
    }

    // MARK: - Slim system prompt

    private fun buildSystemPrompt(
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
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

        val bmrFormula = when {
            profile.usesBodyFatForBMR -> "Katch-McArdle (uses body fat %)"
            profile.bodyFatPercentage != null -> "Mifflin-St Jeor (user disabled the body-fat override in Settings)"
            else -> "Mifflin-St Jeor (body fat not set)"
        }

        val lines = mutableListOf<String>()
        lines.add("You are Coach, an AI nutrition and weight-change assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and ground your recommendations in the user's own data. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        lines.add("")
        lines.add("## How to use the data tools")
        lines.add("You have access to functions that fetch the user's history on demand. The user profile + formulas + forecast below cover what's needed for most questions. Call a tool ONLY when the user asks about specific past dates, longer time ranges, individual meals, or trends that need raw data. Examples:")
        lines.add("- \"How was my weight in March?\" → call get_weight_history(from, to)")
        lines.add("- \"What did I eat last Tuesday?\" → call get_food_entries(from, to)")
        lines.add("- \"What's my data range?\" → call get_data_summary")
        lines.add("Do NOT call tools for questions you can answer from the profile/forecast below.")
        lines.add("")
        lines.add("## User profile")
        lines.add("- Gender: ${profile.gender.name.lowercase()}")
        lines.add("- Age: ${profile.age}")
        val heightStr = if (useMetric) String.format(Locale.US, "%.0f cm", profile.heightCm)
        else String.format(Locale.US, "%.1f in", profile.heightCm / 2.54)
        lines.add("- Height: $heightStr")
        lines.add("- Current weight: ${wUnit(profile.weightKg)}")
        lines.add("- Activity: ${activityEnglish(profile.activityLevel)}")
        lines.add("- Goal: ${goalEnglish(profile.goal)}")
        profile.goalWeightKg?.let { lines.add("- Goal weight: ${wUnit(it)}") }
        profile.bodyFatPercentage?.let { lines.add("- Body fat: ${(it * 100).toInt()}%") }
        profile.goalBodyFatPercentage?.let { lines.add("- Goal body fat: ${(it * 100).toInt()}%") }
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
            forecast.observedWeeklyChangeKg?.let { lines.add("- Observed change (from scale): ${weekly(it)}") }
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
        lines.add("## Data available")
        lines.add("- ${weights.size} weight entries, ${bodyFats.size} body-fat readings, ${foods.size} food entries logged total. Use get_data_summary to see exact date ranges.")
        lines.add("")
        lines.add("When the user asks how to lose or gain, give a concrete calorie target and at least one actionable food or activity change. When they ask expected weight, reference the forecast numbers above.")
        return lines.joinToString("\n")
    }

    // MARK: - OpenAI-compatible tool loop (10 of 13 providers)

    private suspend fun runOpenAIToolLoop(
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        provider: AIProvider,
        tools: CoachTools
    ): String {
        val url = "$baseUrl/chat/completions"
        // OpenAI tool schema: {type:function, function:{name, description, parameters}}
        val toolsArr = JSONArray()
        for (name in CoachTools.TOOL_NAMES) {
            toolsArr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                    put("parameters", parametersSchemaFor(name))
                })
            })
        }
        // Mutable message list: system + history + new user message; grows with
        // assistant tool-call turns + role:tool result rows on each loop pass.
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (msg in history) {
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", newUserMessage))

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("tools", toolsArr)
                put("tool_choice", "auto")
            }
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
            if (!apiKey.isNullOrEmpty()) builder.addHeader("Authorization", "Bearer $apiKey")
            if (provider == AIProvider.OPENROUTER) {
                builder.addHeader("HTTP-Referer", "https://github.com/apoorvdarshan/fud-ai")
                builder.addHeader("X-Title", "Fud AI")
            }
            val raw = RetryPolicy.execute { okHttp.newCall(builder.build()) }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw AiError.InvalidResponse

            // Tool calls take precedence; loop until the model returns plain content.
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                messages.put(message)
                for (i in 0 until toolCalls.length()) {
                    val call = toolCalls.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val id = call.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val argsString = function.optString("arguments", "{}")
                    val args = runCatching { JSONObject(argsString) }.getOrNull() ?: JSONObject()
                    val result = tools.execute(name, args)
                    messages.put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", result)
                    })
                }
                return@repeat
            }
            val content = message.optString("content")
            if (content.isNotEmpty()) return content.trim()
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    // MARK: - Anthropic tool loop

    private suspend fun runAnthropicToolLoop(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        tools: CoachTools
    ): String {
        val url = "$baseUrl/messages"
        // Anthropic tool schema: {name, description, input_schema}
        val toolsArr = JSONArray()
        for (name in CoachTools.TOOL_NAMES) {
            toolsArr.put(JSONObject().apply {
                put("name", name)
                put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                put("input_schema", parametersSchemaFor(name))
            })
        }
        // History as plain text role:content; tool_use / tool_result blocks
        // get appended into messages as the loop runs.
        val messages = JSONArray()
        for (msg in history) {
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", newUserMessage))

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", 1024)
                put("system", systemPrompt)
                put("tools", toolsArr)
                put("messages", messages)
            }
            val raw = RetryPolicy.execute {
                okHttp.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                )
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
            val contentArr = json.optJSONArray("content") ?: throw AiError.InvalidResponse

            // Collect any tool_use blocks; if present, run them and loop with
            // a new user-role message containing tool_result blocks.
            val toolUses = mutableListOf<JSONObject>()
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type") == "tool_use") toolUses.add(block)
            }
            if (toolUses.isNotEmpty()) {
                messages.put(JSONObject().put("role", "assistant").put("content", contentArr))
                val toolResults = JSONArray()
                for (use in toolUses) {
                    val id = use.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val name = use.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val input = use.optJSONObject("input") ?: JSONObject()
                    val result = tools.execute(name, input)
                    toolResults.put(JSONObject().apply {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("content", result)
                    })
                }
                messages.put(JSONObject().put("role", "user").put("content", toolResults))
                return@repeat
            }
            // No tool calls — first text block is the answer.
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") {
                    val text = block.optString("text").trim()
                    if (text.isNotEmpty()) return text
                }
            }
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    // MARK: - Gemini tool loop

    private suspend fun runGeminiToolLoop(
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        tools: CoachTools
    ): String {
        val url = "$baseUrl/models/$model:generateContent"
        // Gemini tool schema: tools=[{functionDeclarations:[{name,description,parameters}]}]
        val declarations = JSONArray()
        for (name in CoachTools.TOOL_NAMES) {
            declarations.put(JSONObject().apply {
                put("name", name)
                put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                put("parameters", parametersSchemaFor(name))
            })
        }
        val toolsObj = JSONObject().put("functionDeclarations", declarations)

        // Build the contents list (history + new user). Each turn's parts may
        // be either text or function_call / function_response.
        val contents = JSONArray()
        for (msg in history) {
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "model"
            contents.put(JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().put(JSONObject().put("text", msg.content)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", newUserMessage)))
        })

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", contents)
                put("tools", JSONArray().put(toolsObj))
            }
            val raw = RetryPolicy.execute {
                okHttp.newCall(
                    Request.Builder()
                        .url(url)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-goog-api-key", apiKey)
                        .post(body.toString().toRequestBody(JSON_MEDIA))
                        .build()
                )
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiError.InvalidResponse
            val content = candidate.optJSONObject("content") ?: throw AiError.InvalidResponse
            val parts = content.optJSONArray("parts") ?: throw AiError.InvalidResponse

            val functionCalls = mutableListOf<JSONObject>()
            val texts = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("functionCall")?.let { functionCalls.add(it) }
                part.optString("text").takeIf { it.isNotEmpty() }?.let { texts.append(it) }
            }
            if (functionCalls.isNotEmpty()) {
                contents.put(JSONObject().apply { put("role", "model"); put("parts", parts) })
                val responseParts = JSONArray()
                for (call in functionCalls) {
                    val name = call.optString("name").takeIf { it.isNotEmpty() } ?: continue
                    val args = call.optJSONObject("args") ?: JSONObject()
                    val resultStr = tools.execute(name, args)
                    val resultObj = runCatching { JSONObject(resultStr) }.getOrNull() ?: JSONObject()
                    responseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", JSONObject().put("content", resultObj))
                        })
                    })
                }
                contents.put(JSONObject().apply { put("role", "user"); put("parts", responseParts) })
                return@repeat
            }
            val combined = texts.toString().trim()
            if (combined.isNotEmpty()) return combined
            throw AiError.InvalidResponse
        }
        throw AiError.Api("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    // MARK: - Tool parameter schemas

    /** Schema is the same shape across all three formats — JSON Schema-ish dict
     *  with type/properties/required. The wrapping is what differs per format. */
    private fun parametersSchemaFor(toolName: String): JSONObject {
        if (toolName == "get_data_summary") {
            return JSONObject().put("type", "object").put("properties", JSONObject())
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("from", JSONObject().put("type", "string").put("description", "ISO date yyyy-MM-dd, inclusive start"))
                put("to", JSONObject().put("type", "string").put("description", "ISO date yyyy-MM-dd, inclusive end"))
                put("limit", JSONObject().put("type", "integer").put("description", "Optional max entries to return"))
            })
            put("required", JSONArray().put("from").put("to"))
        }
    }

    // MARK: - English label helpers (LLM input — not localized)

    private fun activityEnglish(level: ActivityLevel): String = when (level) {
        ActivityLevel.SEDENTARY -> "Sedentary"
        ActivityLevel.LIGHT -> "Light"
        ActivityLevel.MODERATE -> "Moderate"
        ActivityLevel.ACTIVE -> "Active"
        ActivityLevel.VERY_ACTIVE -> "Very Active"
        ActivityLevel.EXTRA_ACTIVE -> "Extra Active"
    }

    private fun goalEnglish(goal: WeightGoal): String = when (goal) {
        WeightGoal.LOSE -> "Lose Weight"
        WeightGoal.MAINTAIN -> "Maintain"
        WeightGoal.GAIN -> "Gain Weight"
    }

    @Suppress("unused")
    private fun Instant.toLocalDateInZone() = this.atZone(ZoneId.systemDefault()).toLocalDate()

    @Suppress("unused")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOOL_ROUNDS = 6
    }
}
