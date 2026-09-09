import Foundation

/// Routes a multi-turn chat (system context + user/assistant message history + new user message)
/// to the currently-selected LLM provider, with **tool calling** so the model can fetch any
/// historical slice of the user's data on demand instead of receiving a fixed-size dump in
/// the system prompt. Tool definitions live next to each provider's HTTP layer (Gemini /
/// Anthropic / OpenAI-compatible) and the executor lives in CoachTools.
struct ChatService {
    enum ChatError: LocalizedError {
        case noAPIKey
        case networkError(Error)
        case apiError(String)
        case invalidResponse

        var errorDescription: String? {
            switch self {
            case .noAPIKey:
                return "No API key configured. Add your key in Settings → AI Provider."
            case .networkError(let err):
                return "Network error: \(err.localizedDescription)"
            case .apiError(let msg):
                return "API error: \(msg)"
            case .invalidResponse:
                return "Could not understand the AI response. Please try again."
            }
        }
    }

    /// Hard cap on the number of tool-call rounds per user message. Generous —
    /// most real questions resolve in 1–2 calls (e.g. summary → range fetch →
    /// answer). Without this cap a misbehaving model could loop forever on
    /// recursive calls.
    private static let maxToolRounds = 6

    // MARK: - Public entry point

    static func sendMessage(
        history: [ChatMessage],
        newUserMessage: String,
        imageData: Data? = nil,
        profile: UserProfile,
        weights: [WeightEntry],
        bodyFats: [BodyFatEntry],
        measurements: [BodyMeasurement] = [],
        foods: [FoodEntry],
        fastingSessions: [FastingSession] = [],
        heightMetric: Bool,
        weightMetric: Bool,
        workoutSessions: [StrengthWorkoutSession] = [],
        workoutPlans: [StrengthWorkoutDayPlan] = [],
        workoutPreferences: StrengthWorkoutPreferences? = nil,
        workoutAccessEnabled: Bool = false
    ) async throws -> String {
        let systemPrompt = buildSystemPrompt(
            profile: profile,
            weights: weights,
            bodyFats: bodyFats,
            measurements: measurements,
            foods: foods,
            fastingSessions: fastingSessions,
            heightMetric: heightMetric,
            weightMetric: weightMetric,
            workoutSessions: workoutSessions,
            workoutPlans: workoutPlans,
            workoutAccessEnabled: workoutAccessEnabled
        )
        let tools = CoachTools(
            weights: weights,
            bodyFats: bodyFats,
            foods: foods,
            fastingSessions: fastingSessions,
            workoutSessions: workoutSessions,
            workoutPlans: workoutPlans,
            workoutPreferences: workoutPreferences,
            workoutPlanWeightUnit: weightMetric ? .kg : .lbs,
            workoutAccessEnabled: workoutAccessEnabled
        )

        let config = AIProviderSettings.currentConfig(requiresVision: imageData != nil)
        func request(
            provider: AIProvider,
            model: String,
            baseURL: String,
            apiKey: String?
        ) async throws -> String {
            guard !provider.requiresAPIKey || apiKey != nil else {
                throw ChatError.noAPIKey
            }

            switch provider.apiFormat {
            case .onDevice:
                return try await callOnDevice(
                    systemPrompt: systemPrompt,
                    history: history,
                    newUserMessage: newUserMessage,
                    imageData: imageData
                )
            case .liteRTLocal:
                let localHistory = history.suffix(8).map { message in
                    Gemma4LocalModelManager.ChatTurn(
                        role: message.role == .user ? .user : .assistant,
                        text: message.content
                    )
                }
                let localInstructions = """
                \(systemPrompt)

                ON-DEVICE MODE
                You cannot call data tools in this mode. Answer only from the profile, forecast, and data summary above. If the question requires unavailable detailed history, say that briefly instead of inventing facts.
                """
                return try await Gemma4LocalModelManager.shared.generate(
                    prompt: newUserMessage,
                    images: imageData.map { [$0] } ?? [],
                    systemPrompt: localInstructions,
                    history: localHistory,
                    maxOutputTokens: AIProviderSettings.maxResponseTokens
                )
            case .gemini:
                return try await callGemini(baseURL: baseURL, model: model, apiKey: apiKey, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, tools: tools)
            case .anthropic:
                return try await callAnthropic(baseURL: baseURL, model: model, apiKey: apiKey, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, tools: tools)
            case .openaiCompatible:
                return try await callOpenAICompatible(baseURL: baseURL, model: model, apiKey: apiKey, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, provider: provider, tools: tools)
            }
        }

        do {
            return try await request(
                provider: config.provider,
                model: config.model,
                baseURL: config.baseURL,
                apiKey: config.apiKey
            )
        } catch {
            if error is CancellationError { throw error }
            let fallback = imageData == nil
                ? AIProviderSettings.currentTextFallbackConfig(
                    excludingPrimary: config.provider,
                    model: config.model
                )
                : AIProviderSettings.currentImageFallbackConfig(
                    excludingPrimary: config.provider,
                    model: config.model
                )
            guard let fallback else { throw error }
            do {
                return try await request(
                    provider: fallback.provider,
                    model: fallback.model,
                    baseURL: fallback.baseURL,
                    apiKey: fallback.apiKey
                )
            } catch let fallbackError {
                if fallbackError is CancellationError { throw fallbackError }
                throw AIRequestErrorPolicy.errorToSurface(
                    primaryProvider: config.provider,
                    primaryError: error,
                    fallbackError: fallbackError
                )
            }
        }
    }

    private static func callOnDevice(
        systemPrompt: String,
        history: [ChatMessage],
        newUserMessage: String,
        imageData: Data?
    ) async throws -> String {
        guard imageData == nil else {
            throw ChatError.apiError("Apple Intelligence is available for text-only conversations.")
        }

        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            let conversation = history.suffix(8).map { message in
                "\(message.role.rawValue.capitalized): \(message.content)"
            }.joined(separator: "\n")
            let prompt = """
            \(conversation.isEmpty ? "" : "Recent conversation:\n\(conversation)\n\n")
            User: \(newUserMessage)
            """
            let onDeviceInstructions = """
            \(systemPrompt)

            ON-DEVICE MODE
            You cannot call data tools in this mode. Answer only from the profile, forecast, and data summary above. If the question requires unavailable detailed history, say that briefly instead of inventing facts.
            """
            return try await OnDeviceAIService.respond(
                to: prompt,
                instructions: onDeviceInstructions
            )
        }
        #endif

        throw ChatError.apiError("Apple Intelligence requires iOS 26 or later on a supported iPhone.")
    }

    // MARK: - System prompt builder

    /// Slim prompt: identity + profile + formulas + forecast summary + a short
    /// "data available" snapshot + tool-use guidance. Bulk history dumps are
    /// gone — Coach calls tools when it actually needs older data, so token
    /// cost per message stays low and Coach can reach **all** of the user's
    /// history (not just the previously-hardcoded last 10/14 entries).
    private static func buildSystemPrompt(
        profile: UserProfile,
        weights: [WeightEntry],
        bodyFats: [BodyFatEntry],
        measurements: [BodyMeasurement] = [],
        foods: [FoodEntry],
        fastingSessions: [FastingSession] = [],
        heightMetric: Bool,
        weightMetric: Bool,
        workoutSessions: [StrengthWorkoutSession] = [],
        workoutPlans: [StrengthWorkoutDayPlan] = [],
        workoutAccessEnabled: Bool = false
    ) -> String {
        let forecast = WeightAnalysisService.compute(weights: weights, foods: foods, profile: profile)
        let currentDateFormatter = DateFormatter()
        currentDateFormatter.dateFormat = "yyyy-MM-dd"
        currentDateFormatter.locale = Locale(identifier: "en_US_POSIX")
        currentDateFormatter.timeZone = .current
        let currentDate = currentDateFormatter.string(from: Date())
        let currentTimeZone = TimeZone.current.identifier

        let wUnit: (Double) -> String = { kg in
            weightMetric ? String(format: "%.1f kg", kg) : String(format: "%.1f lbs", kg * 2.20462)
        }
        let weekly: (Double) -> String = { kg in
            weightMetric ? String(format: "%+.2f kg/week", kg) : String(format: "%+.2f lbs/week", kg * 2.20462)
        }

        let bmrFormula: String
        if profile.usesBodyFatForBMR {
            bmrFormula = "Katch-McArdle (uses body fat %)"
        } else if profile.bodyFatPercentage != nil {
            bmrFormula = "Mifflin-St Jeor (user disabled the body-fat override in Settings)"
        } else {
            bmrFormula = "Mifflin-St Jeor (body fat not set)"
        }

        var lines: [String] = []
        if workoutAccessEnabled {
            lines.append("You are Coach, an AI nutrition, weight-change, and strength-training assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and ground your recommendations in the user's own data. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        } else {
            lines.append("You are Coach, an AI nutrition and weight-change assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and ground your recommendations in the user's own data. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        }
        lines.append("")
        lines.append("## Current date")
        lines.append("- Today: \(currentDate) (\(currentTimeZone))")
        lines.append("- Treat \"today\" as \(currentDate) when choosing tool date ranges.")
        lines.append("")
        lines.append("## How to use the data tools")
        lines.append("You have access to functions that fetch the user's history on demand. The user profile + formulas + forecast below cover what's needed for most questions. Call a tool ONLY when the user asks about specific past dates, longer time ranges, individual meals, or trends that need raw data. Examples:")
        lines.append("- \"How was my weight in March?\" → call get_weight_history(from, to)")
        lines.append("- \"What did I eat last Tuesday?\" → call get_food_entries(from, to)")
        lines.append("- \"How consistent has my fasting been?\" → call get_fasting_history(from, to)")
        lines.append("- \"What's my data range?\" → call get_data_summary")
        if workoutAccessEnabled {
            lines.append("- \"How is my training progressing?\" → call get_training_summary(from, to), then get_workout_history only if individual sets are needed")
            lines.append("- \"What workout do I have planned?\" → call get_workout_plans")
            lines.append("- Use get_workout_preferences when injuries, available equipment, split, schedule, RPE scale, or strength baselines affect the answer.")
        }
        lines.append("Do NOT call tools for questions you can answer from the profile/forecast below.")
        lines.append("")
        lines.append("## User profile")
        lines.append("- Gender: \(profile.gender.rawValue)")
        lines.append("- Age: \(profile.age)")
        lines.append("- Height: \(heightMetric ? String(format: "%.0f cm", profile.heightCm) : String(format: "%.1f in", profile.heightCm / 2.54))")
        lines.append("- Current weight: \(wUnit(profile.weightKg))")
        lines.append("- Activity: \(profile.activityLevel.displayName)")
        lines.append("- Goal: \(profile.goal.displayName)")
        if let goal = profile.goalWeightKg {
            lines.append("- Goal weight: \(wUnit(goal))")
        }
        if let bf = profile.bodyFatPercentage {
            lines.append("- Body fat: \(Int(bf * 100))%")
        }
        if let goalBF = profile.goalBodyFatPercentage {
            lines.append("- Goal body fat: \(Int(goalBF * 100))%")
        }
        lines.append("")
        lines.append("## Formulas in use")
        lines.append("- BMR: \(bmrFormula). Current BMR ≈ \(Int(profile.bmr)) kcal/day")
        lines.append("- TDEE: BMR × activity multiplier ≈ \(Int(profile.tdee)) kcal/day")
        lines.append("- Calorie goal: \(profile.effectiveCalories) kcal/day")
        lines.append("- Macro targets: \(profile.effectiveProtein)g protein, \(profile.effectiveCarbs)g carbs, \(profile.effectiveFat)g fat")
        lines.append("")
        lines.append("## Computed forecast (from their logged data)")
        if forecast.hasEnoughData {
            lines.append("- Days of food logged (last 90d): \(forecast.daysOfFoodData)")
            lines.append("- Weight entries available: \(forecast.weightEntriesUsed)")
            lines.append("- Avg daily intake: \(forecast.avgDailyCalories) kcal")
            lines.append("- Daily energy balance: \(forecast.dailyEnergyBalance >= 0 ? "+" : "")\(forecast.dailyEnergyBalance) kcal")
            lines.append("- Predicted change (from diet): \(weekly(forecast.predictedWeeklyChangeKg))")
            if let observed = forecast.observedWeeklyChangeKg {
                lines.append("- Observed change (from scale): \(weekly(observed))")
            }
            lines.append("- Expected weight in 30 days: \(wUnit(forecast.predictedWeight30dKg))")
            lines.append("- Expected weight in 60 days: \(wUnit(forecast.predictedWeight60dKg))")
            lines.append("- Expected weight in 90 days: \(wUnit(forecast.predictedWeight90dKg))")
            if let days = forecast.daysToGoal {
                lines.append("- Days to goal at current pace: ~\(days) days")
            }
            if forecast.trendsDisagree {
                lines.append("- NOTE: Predicted and observed trends differ by >0.3 kg/week — user may be under-logging food.")
            }
        } else {
            lines.append("- Not enough data yet (need ≥2 days food + ≥2 weights). Encourage the user to log more.")
        }
        lines.append("")
        lines.append("## Data available")
        lines.append("- \(weights.count) weight entries, \(bodyFats.count) body-fat readings, \(foods.count) food entries logged total. Use get_data_summary to see exact date ranges.")
        lines.append("- \(fastingSessions.count) explicitly tracked fasting sessions are available. Never treat a missing food log as a fast.")
        if workoutAccessEnabled {
            lines.append("- \(workoutSessions.count) completed strength workouts and \(workoutPlans.count) dated workout plans are available through the workout tools.")
            lines.append("- Workout logs may guide training, recovery, exercise selection, and progressive-overload advice. Never use estimated workout burn or workout volume to recalculate calorie/macro targets, alter the nutrition forecast, or invent energy expenditure.")
        }
        if let latest = measurements.max(by: { $0.date < $1.date }),
           let summary = latest.promptSummary(gender: profile.gender, heightCm: profile.heightCm) {
            lines.append("")
            lines.append("## Body measurements (latest)")
            lines.append("- \(summary)")
            lines.append("A shrinking waist alongside steady or rising weight is recomposition (fat down, muscle up) — read it that way instead of calling a flat scale a plateau. Treat the US-Navy body-fat figure as an estimate.")
        }
        lines.append("")
        lines.append("When the user asks how to lose or gain, give a concrete calorie target and at least one actionable food or activity change. When they ask expected weight, reference the forecast numbers above.")
        if let userContext = AIProviderSettings.currentUserContext {
            lines.append("")
            lines.append("## User-supplied context (Settings → AI Provider)")
            lines.append(userContext)
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - OpenAI-compatible (/chat/completions) — covers 10 of 13 providers

    /// OpenAI-style tool schema: each tool is `{"type":"function","function":{name, description, parameters}}`.
    private static func openAIToolsArray(for tools: CoachTools) -> [[String: Any]] {
        tools.availableToolNames.map { name -> [String: Any] in
            [
                "type": "function",
                "function": [
                    "name": name,
                    "description": CoachTools.toolDescriptions[name] ?? "",
                    "parameters": CoachTools.parameterSchema(for: name),
                ],
            ]
        }
    }

    private static func callOpenAICompatible(baseURL: String, model: String, apiKey: String?, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, provider: AIProvider, tools: CoachTools) async throws -> String {
        guard let url = URL(string: "\(baseURL)/chat/completions") else {
            throw ChatError.apiError("Invalid API URL.")
        }

        var messages: [[String: Any]] = [["role": "system", "content": systemPrompt]]
        for msg in history {
            messages.append(["role": msg.role.rawValue, "content": msg.content])
        }
        if let imageData {
            messages.append(["role": "user", "content": openAIUserContent(text: newUserMessage, imageData: imageData)])
        } else {
            messages.append(["role": "user", "content": newUserMessage])
        }

        var headers = ["Content-Type": "application/json"]
        if let apiKey {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        if provider == .openrouter {
            headers["HTTP-Referer"] = "https://github.com/apoorvdarshan/fud-ai"
            headers["X-Title"] = "Fud AI"
        }

        let toolsArray = openAIToolsArray(for: tools)

        for _ in 0..<maxToolRounds {
            func request(compactRetry: Bool) async throws -> ([String: Any], [String: Any]) {
                var body: [String: Any] = [
                    "model": model,
                    "messages": messages,
                    "tools": toolsArray,
                    "tool_choice": "auto",
                ]
                body[provider.openAICompatibleTokenLimitKey(for: model)] = AIProviderSettings.maxResponseTokens
                if provider == .openrouter {
                    body["reasoning"] = AIProviderSettings.openRouterReasoningEffort.requestOptions(compactRetry: compactRetry, exclude: false)
                }
                let data = try await send(url: url, headers: headers, body: body, provider: provider)
                guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                else { throw ChatError.invalidResponse }
                let errorMessage = (json["error"] as? [String: Any])?["message"] as? String
                guard let choice = (json["choices"] as? [[String: Any]])?.first else {
                    if let errorMessage, !errorMessage.isEmpty { throw ChatError.apiError(errorMessage) }
                    throw ChatError.invalidResponse
                }
                if (choice["finish_reason"] as? String) == "error" {
                    throw ChatError.apiError(errorMessage ?? "The AI provider returned an error.")
                }
                guard let message = choice["message"] as? [String: Any]
                else { throw ChatError.invalidResponse }
                return (choice, message)
            }

            var (choice, message) = try await request(compactRetry: false)
            let hasToolCalls = !((message["tool_calls"] as? [[String: Any]]) ?? []).isEmpty
            let hasContent = !((message["content"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            let hasReasoning = !((message["reasoning"] as? String) ?? "").isEmpty
                || !((message["reasoning_content"] as? String) ?? "").isEmpty
                || !((message["reasoning_details"] as? [[String: Any]]) ?? []).isEmpty
            if (choice["finish_reason"] as? String) == "length" || (!hasToolCalls && !hasContent && hasReasoning) {
                (choice, message) = try await request(compactRetry: true)
                if (choice["finish_reason"] as? String) == "length" {
                    throw ChatError.apiError("The AI response was truncated twice. Try a shorter question or another model.")
                }
            }

            // Tool calls take precedence — if present, run them and loop.
            if let toolCalls = message["tool_calls"] as? [[String: Any]], !toolCalls.isEmpty {
                // Append the assistant's tool-call message verbatim so the
                // next turn knows which tool_call_ids to respond to.
                messages.append(message)
                for call in toolCalls {
                    guard let function = call["function"] as? [String: Any],
                          let name = function["name"] as? String,
                          let id = call["id"] as? String else { continue }
                    let argsString = function["arguments"] as? String ?? "{}"
                    let args = (try? JSONSerialization.jsonObject(with: Data(argsString.utf8))) as? [String: Any] ?? [:]
                    let result = tools.execute(name: name, arguments: args)
                    messages.append([
                        "role": "tool",
                        "tool_call_id": id,
                        "content": result,
                    ])
                }
                continue
            }

            if let content = message["content"] as? String {
                return content.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            throw ChatError.invalidResponse
        }
        throw ChatError.apiError("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    private static func openAIUserContent(text: String, imageData: Data) -> [[String: Any]] {
        [
            [
                "type": "image_url",
                "image_url": ["url": "data:image/jpeg;base64,\(imageData.base64EncodedString())"],
            ],
            ["type": "text", "text": text],
        ]
    }

    // MARK: - Anthropic Messages API

    /// Anthropic tool schema: `{name, description, input_schema}`. Tool calls
    /// arrive as `tool_use` content blocks; results go back as `tool_result`
    /// blocks within a user message.
    private static func anthropicToolsArray(for tools: CoachTools) -> [[String: Any]] {
        tools.availableToolNames.map { name -> [String: Any] in
            [
                "name": name,
                "description": CoachTools.toolDescriptions[name] ?? "",
                "input_schema": CoachTools.parameterSchema(for: name),
            ]
        }
    }

    private static func callAnthropic(baseURL: String, model: String, apiKey: String?, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, tools: CoachTools) async throws -> String {
        guard let apiKey else { throw ChatError.noAPIKey }
        guard let url = URL(string: "\(baseURL)/messages") else {
            throw ChatError.apiError("Invalid API URL.")
        }
        var messages: [[String: Any]] = []
        for msg in history {
            messages.append(["role": msg.role.rawValue, "content": msg.content])
        }
        if let imageData {
            messages.append(["role": "user", "content": anthropicUserContent(text: newUserMessage, imageData: imageData)])
        } else {
            messages.append(["role": "user", "content": newUserMessage])
        }

        let toolsArray = anthropicToolsArray(for: tools)
        let headers = [
            "Content-Type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
        ]

        for _ in 0..<maxToolRounds {
            let body: [String: Any] = [
                "model": model,
                "max_tokens": AIProviderSettings.maxResponseTokens,
                "system": systemPrompt,
                "tools": toolsArray,
                "messages": messages,
            ]
            let data = try await send(url: url, headers: headers, body: body, provider: .anthropic)
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let contentArray = json["content"] as? [[String: Any]]
            else {
                throw ChatError.invalidResponse
            }

            // Anthropic returns "stop_reason": "tool_use" alongside content blocks
            // mixing text + tool_use. Run all tool_use blocks, append their
            // results, and loop.
            let toolUses = contentArray.filter { ($0["type"] as? String) == "tool_use" }
            if !toolUses.isEmpty {
                // Echo the assistant's full content array back so Anthropic
                // can pair tool_result blocks to their tool_use ids.
                messages.append(["role": "assistant", "content": contentArray])
                var toolResults: [[String: Any]] = []
                for use in toolUses {
                    guard let id = use["id"] as? String, let name = use["name"] as? String else { continue }
                    let input = (use["input"] as? [String: Any]) ?? [:]
                    let result = tools.execute(name: name, arguments: input)
                    toolResults.append([
                        "type": "tool_result",
                        "tool_use_id": id,
                        "content": result,
                    ])
                }
                messages.append(["role": "user", "content": toolResults])
                continue
            }

            // No tool calls → first text block is the answer.
            if let firstText = contentArray.first(where: { ($0["type"] as? String) == "text" }),
               let text = firstText["text"] as? String {
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            throw ChatError.invalidResponse
        }
        throw ChatError.apiError("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    private static func anthropicUserContent(text: String, imageData: Data) -> [[String: Any]] {
        [
            [
                "type": "image",
                "source": [
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": imageData.base64EncodedString(),
                ],
            ],
            ["type": "text", "text": text],
        ]
    }

    // MARK: - Gemini (v1beta generateContent with system_instruction + tools)

    /// Gemini tool schema: `{"functionDeclarations": [{name, description, parameters}]}`
    /// where parameters use OpenAPI type names (object/string/integer).
    private static func geminiToolsObject(for tools: CoachTools) -> [String: Any] {
        let declarations: [[String: Any]] = tools.availableToolNames.map { name in
            [
                "name": name,
                "description": CoachTools.toolDescriptions[name] ?? "",
                "parameters": CoachTools.parameterSchema(for: name),
            ]
        }
        return ["functionDeclarations": declarations]
    }

    /// Gemini 3 models attach an identifier to every function call. The matching
    /// response must echo that identifier so parallel and multi-round calls are
    /// associated with the correct result.
    static func geminiFunctionResponsePart(
        for call: [String: Any],
        result: Any
    ) -> [String: Any]? {
        guard let name = call["name"] as? String, !name.isEmpty else { return nil }
        var functionResponse: [String: Any] = [
            "name": name,
            "response": ["content": result],
        ]
        if let id = call["id"] as? String, !id.isEmpty {
            functionResponse["id"] = id
        }
        return ["functionResponse": functionResponse]
    }

    private static func callGemini(baseURL: String, model: String, apiKey: String?, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, tools: CoachTools) async throws -> String {
        guard let apiKey else { throw ChatError.noAPIKey }
        guard let url = URL(string: "\(baseURL)/models/\(model):generateContent") else {
            throw ChatError.apiError("Invalid API URL.")
        }

        var contents: [[String: Any]] = []
        for msg in history {
            let role = msg.role == .user ? "user" : "model"
            contents.append(["role": role, "parts": [["text": msg.content]]])
        }
        contents.append(["role": "user", "parts": geminiUserParts(text: newUserMessage, imageData: imageData)])

        let toolsObj = geminiToolsObject(for: tools)

        for _ in 0..<maxToolRounds {
            let body: [String: Any] = [
                "systemInstruction": ["parts": [["text": systemPrompt]]],
                "contents": contents,
                "tools": [toolsObj],
            ]
            let data = try await send(
                url: url,
                headers: ["Content-Type": "application/json", "X-goog-api-key": apiKey],
                body: body,
                provider: .gemini
            )
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let candidates = json["candidates"] as? [[String: Any]],
                  let candidate = candidates.first,
                  let content = candidate["content"] as? [String: Any],
                  let parts = content["parts"] as? [[String: Any]]
            else {
                throw ChatError.invalidResponse
            }

            // Function calls + plain text can both appear. Run any function
            // calls and loop; otherwise concatenate text and return.
            let functionCalls = parts.compactMap { $0["functionCall"] as? [String: Any] }
            if !functionCalls.isEmpty {
                // Echo the model's full parts back so Gemini sees its own
                // function call when matching responses.
                contents.append(["role": "model", "parts": parts])
                var responseParts: [[String: Any]] = []
                for call in functionCalls {
                    guard let name = call["name"] as? String else { continue }
                    let args = (call["args"] as? [String: Any]) ?? [:]
                    let resultString = tools.execute(name: name, arguments: args)
                    let resultObj = (try? JSONSerialization.jsonObject(with: Data(resultString.utf8))) ?? [:]
                    if let responsePart = geminiFunctionResponsePart(for: call, result: resultObj) {
                        responseParts.append(responsePart)
                    }
                }
                contents.append(["role": "user", "parts": responseParts])
                continue
            }

            let texts = parts.compactMap { $0["text"] as? String }.joined()
            if !texts.isEmpty {
                return texts.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            throw ChatError.invalidResponse
        }
        throw ChatError.apiError("Coach exceeded the tool-call round limit. Try rephrasing your question.")
    }

    private static func geminiUserParts(text: String, imageData: Data?) -> [[String: Any]] {
        var parts: [[String: Any]] = []
        if let imageData {
            parts.append([
                "inlineData": [
                    "mimeType": "image/jpeg",
                    "data": imageData.base64EncodedString(),
                ],
            ])
        }
        parts.append(["text": text])
        return parts
    }

    // MARK: - Shared HTTP

    private static func send(
        url: URL,
        headers: [String: String],
        body: [String: Any],
        provider: AIProvider
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if let timeout = AIProviderSettings.requestTimeout(for: provider) {
            request.timeoutInterval = timeout
        }
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        // Retry transient overload responses (503/429/529) with exponential backoff: 1s, 2s, 4s.
        let retryDelaysNs: [UInt64] = [1_000_000_000, 2_000_000_000, 4_000_000_000]
        var lastError: ChatError = .apiError("Request failed")

        for attempt in 0...retryDelaysNs.count {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await URLSession.shared.data(for: request)
            } catch {
                throw ChatError.networkError(error)
            }

            guard let http = response as? HTTPURLResponse else { return data }

            if http.statusCode == 200 { return data }

            let parsedRaw = parseErrorMessage(from: data) ?? ""
            let parsed = parsedRaw.isEmpty ? "HTTP \(http.statusCode)" : parsedRaw
            lastError = .apiError(friendlyMessage(for: http.statusCode, raw: parsed))

            let isRetryable = http.statusCode == 503
                           || http.statusCode == 529
                           || http.statusCode == 429
            if isRetryable && attempt < retryDelaysNs.count {
                try? await Task.sleep(nanoseconds: retryDelaysNs[attempt])
                continue
            }
            throw lastError
        }
        throw lastError
    }

    private static func parseErrorMessage(from data: Data) -> String? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        if let error = json["error"] as? [String: Any], let message = error["message"] as? String {
            return message
        }
        if let message = json["error"] as? String {
            return message
        }
        return nil
    }

    private static func friendlyMessage(for status: Int, raw: String) -> String {
        let keyRejected = "Your API key was rejected. Open Settings → AI Provider and re-paste a valid key."
        // A bad/expired Gemini key comes back as HTTP 400 (INVALID_ARGUMENT), not 401/403, so
        // match the key-invalid markers in the provider message (mirrors Android #99/#113).
        let hasKeyInvalidMarker = raw.range(of: "api key not valid", options: .caseInsensitive) != nil
            || raw.range(of: "api_key_invalid", options: .caseInsensitive) != nil
            || raw.range(of: "api key expired", options: .caseInsensitive) != nil
            || raw.range(of: "api_key_expired", options: .caseInsensitive) != nil
        switch status {
        case 503, 529:
            return "The AI provider is overloaded right now. We retried a few times — please try again in a minute, or switch to a different provider/model in Settings → AI Provider."
        case 429:
            return "Rate limit hit on your API key. Wait a minute, or switch to another provider in Settings → AI Provider."
        case 400 where hasKeyInvalidMarker:
            return keyRejected
        case 401, 403:
            return keyRejected
        default:
            return raw
        }
    }
}
