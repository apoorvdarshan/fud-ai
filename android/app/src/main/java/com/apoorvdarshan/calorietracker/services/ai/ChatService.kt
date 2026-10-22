package com.apoorvdarshan.calorietracker.services.ai

import com.apoorvdarshan.calorietracker.data.AICredentialRegistry
import com.apoorvdarshan.calorietracker.data.AIRoute
import com.apoorvdarshan.calorietracker.data.ExerciseItem
import com.apoorvdarshan.calorietracker.data.PreferencesStore
import com.apoorvdarshan.calorietracker.models.AIProvider
import com.apoorvdarshan.calorietracker.models.BodyFatEntry
import com.apoorvdarshan.calorietracker.models.BodyMeasurement
import com.apoorvdarshan.calorietracker.models.EnergyUnit
import com.apoorvdarshan.calorietracker.models.ChatMessage
import com.apoorvdarshan.calorietracker.models.CoachProposalCollector
import com.apoorvdarshan.calorietracker.models.FoodEntry
import com.apoorvdarshan.calorietracker.models.FastingSession
import com.apoorvdarshan.calorietracker.models.UserProfile
import com.apoorvdarshan.calorietracker.models.WorkoutDayPlan
import com.apoorvdarshan.calorietracker.models.WorkoutPreferences
import com.apoorvdarshan.calorietracker.models.WorkoutSession
import com.apoorvdarshan.calorietracker.models.WorkoutWeightUnit
import com.apoorvdarshan.calorietracker.models.WeightEntry
import com.apoorvdarshan.calorietracker.services.hosted.AiAccessMode
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiAccess
import com.apoorvdarshan.calorietracker.services.hosted.HostedAiConstants
import com.apoorvdarshan.calorietracker.services.ondevice.LocalGemmaRuntime
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

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
    private val credentials: AICredentialRegistry,
    private val okHttp: OkHttpClient = FoodAnalysisService.defaultClient,
    private val localGemma: LocalGemmaRuntime? = null,
    private val hosted: HostedAiAccess? = null
) {

    suspend fun sendMessage(
        history: List<ChatMessage>,
        newUserMessage: String,
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        imageBytes: ByteArray? = null,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        workoutPreferences: WorkoutPreferences = WorkoutPreferences(),
        workoutPlanWeightUnit: WorkoutWeightUnit = WorkoutWeightUnit.LBS,
        exerciseLibrary: List<ExerciseItem> = emptyList(),
        proposals: CoachProposalCollector? = null
    ): String {
        val energyUnit = prefs.energyUnit.first()
        val userContext = prefs.userContext.first()
        fun systemPromptFor(proposalCards: Boolean): String {
            val baseSystemPrompt = buildSystemPrompt(
                profile = profile,
                weights = weights,
                bodyFats = bodyFats,
                measurements = measurements,
                foods = foods,
                fastingSessions = fastingSessions,
                heightMetric = heightMetric,
                weightMetric = weightMetric,
                workoutSessions = workoutSessions,
                workoutPlans = workoutPlans,
                canProposeWorkoutChanges = proposalCards,
                energyUnit = energyUnit
            )
            return if (userContext.isNotBlank())
                "$baseSystemPrompt\n\n## User-provided context\n$userContext"
            else baseSystemPrompt
        }
        val systemPrompt = systemPromptFor(proposalCards = proposals != null)
        // The on-device model answers without tools, so it is never told about proposal cards it
        // cannot make (the reply would point the user at a card that never appears).
        val toolFreeSystemPrompt = { if (proposals == null) systemPrompt else systemPromptFor(proposalCards = false) }
        val tools = CoachTools(
            weights = weights,
            bodyFats = bodyFats,
            foods = foods,
            fastingSessions = fastingSessions,
            workoutSessions = workoutSessions,
            workoutPlans = workoutPlans,
            workoutPreferences = workoutPreferences,
            workoutPlanWeightUnit = workoutPlanWeightUnit,
            exerciseLibrary = exerciseLibrary,
            proposals = proposals
        )

        if (hosted != null && prefs.aiAccessMode.first() == AiAccessMode.HOSTED) {
            // Hosted Coach: same Gemini tool loop, sent through the Worker's /gemini
            // allow-list. Every round is one metered action; no BYOK fallback.
            hosted.requireEntitlement()
            proposals?.reset()
            return runGeminiToolLoop(
                systemPrompt, history, newUserMessage, tools, imageBytes,
                maxToolResultChars = HostedAiConstants.MAX_TOOL_RESULT_CHARS
            ) { body ->
                hosted.client.gemini(body.toString())
            }
        }

        val useSeparateTextProvider = imageBytes == null && prefs.separateTextProviderEnabled.first()
        val provider = if (useSeparateTextProvider) {
            prefs.selectedTextAIProvider.first()
        } else {
            prefs.selectedAIProvider.first()
        }
        val model = if (useSeparateTextProvider) {
            provider.supportedTextModelOrDefault(prefs.selectedTextAIModel.first())
        } else {
            provider.supportedModelOrDefault(prefs.selectedAIModel.first())
        }
        val baseUrl = prefs.customBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        val apiKey = credentials.routeSecret(
            if (useSeparateTextProvider) AIRoute.TEXT else AIRoute.PRIMARY,
            provider
        )
        val maxTokens = prefs.maxResponseTokens.first()
        val requestTimeoutSeconds = prefs.aiRequestTimeoutSeconds.first()

        return try {
            proposals?.reset()
            runProvider(
                provider, model, baseUrl, apiKey, systemPrompt, history, newUserMessage,
                tools, imageBytes, maxTokens, requestTimeoutSeconds, toolFreeSystemPrompt
            )
        } catch (primaryError: Throwable) {
            if (primaryError is kotlinx.coroutines.CancellationException) throw primaryError
            val primaryRoute = if (useSeparateTextProvider) AIRoute.TEXT else AIRoute.PRIMARY
            val fallback = currentFallbackConfig(
                hasImage = imageBytes != null,
                primary = provider,
                primaryModel = model,
                primaryBaseUrl = baseUrl,
                primaryCredentialId = credentials.routeCredential(primaryRoute, provider)?.id
            ) ?: throw primaryError
            // The fallback answers from scratch, so cards from the failed attempt are dropped.
            proposals?.reset()
            runProvider(
                fallback.provider, fallback.model, fallback.baseUrl, fallback.apiKey,
                systemPrompt, history, newUserMessage, tools, imageBytes, maxTokens,
                requestTimeoutSeconds, toolFreeSystemPrompt
            )
        }
    }

    private suspend fun runProvider(
        provider: AIProvider,
        model: String,
        baseUrl: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int,
        requestTimeoutSeconds: Int,
        toolFreeSystemPrompt: () -> String
    ): String {
        if (provider == AIProvider.LOCAL_GEMMA) {
            val conversation = buildString {
                history.takeLast(12).forEach { message ->
                    append(if (message.role == ChatMessage.Role.USER) "User: " else "Assistant: ")
                    appendLine(message.contextContent)
                }
                append("User: ")
                append(newUserMessage)
            }
            return localGemma?.generate(
                prompt = conversation,
                images = imageBytes?.let(::listOf).orEmpty(),
                maxOutputTokens = maxTokens,
                systemInstruction = toolFreeSystemPrompt()
            ) ?: throw AiError.Api("The on-device Gemma runtime is unavailable.")
        }
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) throw AiError.NoApiKey
        if (baseUrl.isEmpty()) throw AiError.InvalidUrl(baseUrl)
        val requestClient = FoodAnalysisService.clientForProvider(okHttp, provider, requestTimeoutSeconds)
        return when (provider.apiFormat) {
            AIProvider.ApiFormat.GEMINI -> {
                val url = "$baseUrl/models/$model:generateContent"
                val key = apiKey!!
                runGeminiToolLoop(systemPrompt, history, newUserMessage, tools, imageBytes) { body ->
                    RetryPolicy.execute {
                        requestClient.newCall(
                            Request.Builder()
                                .url(url)
                                .addHeader("Content-Type", "application/json")
                                .addHeader("X-goog-api-key", key)
                                .post(body.toString().toRequestBody(JSON_MEDIA))
                                .build()
                        )
                    }
                }
            }
            AIProvider.ApiFormat.ANTHROPIC -> runAnthropicToolLoop(requestClient, baseUrl, model, apiKey!!, systemPrompt, history, newUserMessage, tools, imageBytes, maxTokens)
            AIProvider.ApiFormat.OPENAI_COMPATIBLE -> runOpenAIToolLoop(requestClient, baseUrl, model, apiKey, systemPrompt, history, newUserMessage, provider, tools, imageBytes, maxTokens)
            AIProvider.ApiFormat.LOCAL -> error("Local inference must be dispatched before network setup.")
        }
    }

    private suspend fun currentFallbackConfig(
        hasImage: Boolean,
        primary: AIProvider,
        primaryModel: String,
        primaryBaseUrl: String,
        primaryCredentialId: String?
    ): ChatFallbackConfig? {
        prefs.migrateFallbackBaseUrls()
        val enabled = if (hasImage) prefs.fallbackEnabled.first() else prefs.textFallbackEnabled.first()
        if (!enabled) return null
        val provider = if (hasImage) {
            prefs.selectedFallbackProvider.first()
        } else {
            prefs.selectedTextFallbackProvider.first()
        }
        val model = if (hasImage) {
            provider.supportedModelOrDefault(prefs.selectedFallbackModel.first())
        } else {
            provider.supportedTextModelOrDefault(prefs.selectedTextFallbackModel.first())
        }
        val baseUrl = prefs.fallbackCustomBaseUrl(provider).first()?.takeIf { it.isNotEmpty() } ?: provider.baseUrl
        val route = if (hasImage) AIRoute.IMAGE_FALLBACK else AIRoute.TEXT_FALLBACK
        val credential = credentials.routeCredential(route, provider)
        // A second key of the same provider is a different request, even when provider,
        // model and server are identical.
        if (provider == primary && model == primaryModel && baseUrl == primaryBaseUrl &&
            credential?.id == primaryCredentialId
        ) {
            return null
        }
        val apiKey = credentials.routeSecret(route, provider)
        if (provider.requiresApiKey && apiKey.isNullOrEmpty()) return null
        if (provider != AIProvider.LOCAL_GEMMA && baseUrl.isEmpty()) return null
        return ChatFallbackConfig(provider, model, baseUrl, apiKey)
    }

    private data class ChatFallbackConfig(
        val provider: AIProvider,
        val model: String,
        val baseUrl: String,
        val apiKey: String?
    )

    // MARK: - Slim system prompt

    private fun buildSystemPrompt(
        profile: UserProfile,
        weights: List<WeightEntry>,
        bodyFats: List<BodyFatEntry>,
        measurements: List<BodyMeasurement> = emptyList(),
        foods: List<FoodEntry>,
        fastingSessions: List<FastingSession> = emptyList(),
        heightMetric: Boolean,
        weightMetric: Boolean,
        workoutSessions: List<WorkoutSession> = emptyList(),
        workoutPlans: List<WorkoutDayPlan> = emptyList(),
        canProposeWorkoutChanges: Boolean = false,
        energyUnit: EnergyUnit = EnergyUnit.Default
    ): String {
        val zone = ZoneId.systemDefault()
        val currentDate = LocalDate.now(zone).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val currentTimeZone = zone.id

        val lines = mutableListOf<String>()
        lines.add("You are Coach, an AI nutrition, weight-change, and strength-training assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and ground your recommendations in the user's own data. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        lines.add("")
        lines.add("## Current date")
        lines.add("- Today: $currentDate ($currentTimeZone)")
        lines.add("- Treat \"today\" as $currentDate when choosing tool date ranges.")
        lines.add("")
        lines.add("## How to use the data tools")
        lines.add("You have access to functions that fetch the user's history on demand. The user profile + formulas + forecast below cover what's needed for most questions. Call a tool ONLY when the user asks about specific past dates, longer time ranges, individual meals, or trends that need raw data. Examples:")
        lines.add("- \"How was my weight in March?\" → call get_weight_history(from, to)")
        lines.add("- \"What did I eat last Tuesday?\" → call get_food_entries(from, to)")
        lines.add("- \"How consistent has my fasting been?\" → call get_fasting_history(from, to)")
        lines.add("- \"What's my data range?\" → call get_data_summary")
        lines.add("- \"How is my training progressing?\" → call get_training_summary(from, to), then get_workout_history only if individual sets are needed")
        lines.add("- \"What did I bench last?\" / one-lift trends → call get_exercise_lift_history(exercise)")
        lines.add("- \"What workout do I have planned?\" → call get_workout_plans")
        lines.add("- A photo of a gym machine, or \"is this exercise in my library?\" → identify the exercise, then call search_exercise_library(query)")
        lines.add("- Use get_workout_preferences when injuries, available equipment, split, schedule, RPE scale, or strength baselines affect the answer.")
        if (canProposeWorkoutChanges) {
            lines.add("- \"Add this to my workout\" → search_exercise_library, then propose_add_exercise_to_day. A gym machine that is not in the library → propose_custom_exercise. These only show the user a card to confirm; nothing is saved until they tap Add, so never say a change was made.")
        }
        lines.add("Do NOT call tools for questions you can answer from the profile/forecast below.")
        lines.add("")
        lines.addAll(
            CoachContext.summaryLines(
                profile = profile,
                weights = weights,
                bodyFats = bodyFats,
                measurements = measurements,
                foods = foods,
                fastingSessions = fastingSessions,
                heightMetric = heightMetric,
                weightMetric = weightMetric,
                workoutSessions = workoutSessions,
                workoutPlans = workoutPlans,
                energyUnit = energyUnit
            )
        )
        lines.add("")
        lines.add("When the user asks how to lose or gain, give a concrete calorie target and at least one actionable food or activity change. When they ask expected weight, reference the forecast numbers above.")
        return lines.joinToString("\n")
    }

    // MARK: - OpenAI-compatible tool loop (10 of 13 providers)

    private suspend fun runOpenAIToolLoop(
        requestClient: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        provider: AIProvider,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int
    ): String {
        val reasoningEffort = prefs.openRouterReasoningEffort.first()
        val url = "$baseUrl/chat/completions"
        // OpenAI tool schema: {type:function, function:{name, description, parameters}}
        val toolsArr = JSONArray()
        for (name in tools.availableToolNames) {
            toolsArr.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                    put("parameters", JSONObject(CoachTools.parameterSchemaFor(name)))
                })
            })
        }
        // Mutable message list: system + history + new user message; grows with
        // assistant tool-call turns + role:tool result rows on each loop pass.
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
        for (msg in history) {
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.contextContent))
        }
        messages.put(JSONObject().put("role", "user").put("content", openAIUserContent(newUserMessage, imageBytes)))

        repeat(MAX_TOOL_ROUNDS) {
            suspend fun request(compactRetry: Boolean): Pair<JSONObject, JSONObject> {
                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("tools", toolsArr)
                    put("tool_choice", "auto")
                    put(OpenAICompatibleClient.tokenLimitParameter(provider, model), maxTokens)
                    OpenAICompatibleClient.applyProviderOptions(this, provider)
                    OpenAICompatibleClient.toolReasoningEffort(provider, model)?.let {
                        put("reasoning_effort", it)
                    }
                    if (provider == AIProvider.OPENROUTER) {
                        reasoningEffort.requestOptions(compactRetry, exclude = false)?.let {
                            put("reasoning", JSONObject(it))
                        }
                    }
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
                val raw = RetryPolicy.execute { requestClient.newCall(builder.build()) }
                val json = runCatching { JSONObject(raw) }.getOrNull() ?: throw AiError.InvalidResponse
                val error = json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw if (error != null) AiError.Api(error) else AiError.InvalidResponse
                if (choice.optString("finish_reason") == "error") {
                    throw AiError.Api(error ?: "The AI provider returned an error.")
                }
                val message = choice.optJSONObject("message") ?: throw AiError.InvalidResponse
                return choice to message
            }

            var (choice, message) = request(compactRetry = false)
            val hasToolCalls = (message.optJSONArray("tool_calls")?.length() ?: 0) > 0
            val hasContent = message.optString("content").isNotBlank()
            val hasReasoning = message.optString("reasoning").isNotBlank() ||
                message.optString("reasoning_content").isNotBlank() ||
                (message.optJSONArray("reasoning_details")?.length() ?: 0) > 0
            if (choice.optString("finish_reason") == "length" || (!hasToolCalls && !hasContent && hasReasoning)) {
                val retry = request(compactRetry = true)
                choice = retry.first
                message = retry.second
                if (choice.optString("finish_reason") == "length") {
                    throw AiError.Api("The AI response was truncated twice. Try a shorter question or another model.")
                }
            }

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
        requestClient: OkHttpClient,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxTokens: Int
    ): String {
        val url = "$baseUrl/messages"
        // Anthropic tool schema: {name, description, input_schema}
        val toolsArr = JSONArray()
        for (name in tools.availableToolNames) {
            toolsArr.put(JSONObject().apply {
                put("name", name)
                put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                put("input_schema", JSONObject(CoachTools.parameterSchemaFor(name)))
            })
        }
        // History as plain text role:content; tool_use / tool_result blocks
        // get appended into messages as the loop runs.
        val messages = JSONArray()
        for (msg in history) {
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.contextContent))
        }
        messages.put(JSONObject().put("role", "user").put("content", anthropicUserContent(newUserMessage, imageBytes)))

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("model", model)
                put("max_tokens", maxTokens)
                put("system", systemPrompt)
                put("tools", toolsArr)
                put("messages", messages)
            }
            val raw = RetryPolicy.execute {
                requestClient.newCall(
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

    /**
     * [send] posts one `generateContent` body (BYOK Gemini or the hosted proxy) and returns its raw JSON.
     * [maxToolResultChars] trims oversized tool results (the hosted proxy rejects them).
     */
    private suspend fun runGeminiToolLoop(
        systemPrompt: String,
        history: List<ChatMessage>,
        newUserMessage: String,
        tools: CoachTools,
        imageBytes: ByteArray?,
        maxToolResultChars: Int? = null,
        send: suspend (JSONObject) -> String
    ): String {
        // Gemini tool schema: tools=[{functionDeclarations:[{name,description,parameters}]}]
        val declarations = JSONArray()
        for (name in tools.availableToolNames) {
            declarations.put(JSONObject().apply {
                put("name", name)
                put("description", CoachTools.TOOL_DESCRIPTIONS[name] ?: "")
                put("parameters", JSONObject(CoachTools.parameterSchemaFor(name)))
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
                put("parts", JSONArray().put(JSONObject().put("text", msg.contextContent)))
            })
        }
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", geminiUserParts(newUserMessage, imageBytes))
        })

        repeat(MAX_TOOL_ROUNDS) {
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                put("contents", contents)
                put("tools", JSONArray().put(toolsObj))
            }
            val raw = send(body)
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
                    val resultStr = tools.execute(name, args).let { result ->
                        maxToolResultChars?.let { ToolResultLimiter.fit(result, it) } ?: result
                    }
                    val resultObj = runCatching { JSONObject(resultStr) }.getOrNull() ?: JSONObject()
                    responseParts.put(JSONObject().apply {
                        put("functionResponse", JSONObject().apply {
                            put("name", name)
                            put("response", JSONObject().put("content", resultObj))
                            call.optString("id").takeIf { it.isNotEmpty() }?.let { put("id", it) }
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

    private fun openAIUserContent(text: String, imageBytes: ByteArray?): Any {
        if (imageBytes == null) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,${base64(imageBytes)}")
                    )
            )
        }
    }

    private fun anthropicUserContent(text: String, imageBytes: ByteArray?): Any {
        if (imageBytes == null) return text
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", text))
            put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", "image/jpeg")
                            .put("data", base64(imageBytes))
                    )
            )
        }
    }

    private fun geminiUserParts(text: String, imageBytes: ByteArray?): JSONArray =
        JSONArray().apply {
            imageBytes?.let {
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", base64(it))
                    )
                )
            }
            put(JSONObject().put("text", text))
        }

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    @Suppress("unused")
    private fun Instant.toLocalDateInZone() = this.atZone(ZoneId.systemDefault()).toLocalDate()

    @Suppress("unused")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault())

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOOL_ROUNDS = 6
    }
}
