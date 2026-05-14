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
        case subscriptionRequired

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
            case .subscriptionRequired:
                return "Fud AI Plus is not active. Subscribe or switch back to Bring Your Own Key in Settings."
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
        foods: [FoodEntry],
        useMetric: Bool
    ) async throws -> String {
        let systemPrompt = buildSystemPrompt(
            profile: profile,
            weights: weights,
            bodyFats: bodyFats,
            foods: foods,
            useMetric: useMetric
        )
        let tools = CoachTools(weights: weights, bodyFats: bodyFats, foods: foods, useMetric: useMetric)

        let usingFudAIPlus = AIAccessSettings.isUsingFudAIPlus
        let provider: AIProvider = usingFudAIPlus ? .gemini : AIProviderSettings.selectedProvider
        let model = usingFudAIPlus ? "gemini-2.5-flash-lite" : AIProviderSettings.selectedModel
        let baseURL = usingFudAIPlus ? AIProvider.gemini.baseURL : AIProviderSettings.currentBaseURL

        if usingFudAIPlus, !AIAccessSettings.hasActivePlusEntitlement {
            throw ChatError.subscriptionRequired
        }

        guard usingFudAIPlus || AIProviderSettings.currentAPIKey != nil || provider == .ollama else {
            throw ChatError.noAPIKey
        }

        switch provider.apiFormat {
        case .gemini:
            return try await callGemini(baseURL: baseURL, model: model, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, tools: tools)
        case .anthropic:
            return try await callAnthropic(baseURL: baseURL, model: model, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, tools: tools)
        case .openaiCompatible:
            return try await callOpenAICompatible(baseURL: baseURL, model: model, systemPrompt: systemPrompt, history: history, newUserMessage: newUserMessage, imageData: imageData, provider: provider, tools: tools)
        }
    }

    // MARK: - System prompt builder

    /// Slim prompt: identity + profile + formulas + forecast summary + a short
    /// "data available" snapshot + tool-use guidance. Bulk history dumps are
    /// gone — Coach calls tools when it actually needs older data, so token
    /// cost per message stays low and Coach can reach **all** of the user's
    /// history (not just the previously-hardcoded last 10/14 entries).
    private static func buildSystemPrompt(profile: UserProfile, weights: [WeightEntry], bodyFats: [BodyFatEntry], foods: [FoodEntry], useMetric: Bool) -> String {
        let forecast = WeightAnalysisService.compute(weights: weights, foods: foods, profile: profile)
        let currentDateFormatter = DateFormatter()
        currentDateFormatter.dateFormat = "yyyy-MM-dd"
        currentDateFormatter.locale = Locale(identifier: "en_US_POSIX")
        currentDateFormatter.timeZone = .current
        let currentDate = currentDateFormatter.string(from: Date())
        let currentTimeZone = TimeZone.current.identifier

        let wUnit: (Double) -> String = { kg in
            useMetric ? String(format: "%.1f kg", kg) : String(format: "%.1f lbs", kg * 2.20462)
        }
        let weekly: (Double) -> String = { kg in
            useMetric ? String(format: "%+.2f kg/week", kg) : String(format: "%+.2f lbs/week", kg * 2.20462)
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
        lines.append("You are Coach, an AI nutrition and weight-change assistant inside a calorie tracking app. Answer in plain English, be specific and factual, and ground your recommendations in the user's own data. Avoid medical advice; when relevant, suggest consulting a doctor. Be concise — 2–5 sentences per response unless the user asks for detail.")
        lines.append("")
        lines.append("## Current date")
        lines.append("- Today: \(currentDate) (\(currentTimeZone))")
        lines.append("- Treat \"today\" as \(currentDate) when choosing tool date ranges.")
        lines.append("")
        lines.append("## How to use the data tools")
        lines.append("You have access to functions that fetch the user's history on demand. The user profile + formulas + forecast below cover what's needed for most questions. Call a tool ONLY when the user asks about specific past dates, longer time ranges, individual meals, or trends that need raw data. Examples:")
        lines.append("- \"How was my weight in March?\" → call get_weight_history(from, to)")
        lines.append("- \"What did I eat last Tuesday?\" → call get_food_entries(from, to)")
        lines.append("- \"What's my data range?\" → call get_data_summary")
        lines.append("Do NOT call tools for questions you can answer from the profile/forecast below.")
        lines.append("")
        lines.append("## User profile")
        lines.append("- Gender: \(profile.gender.rawValue)")
        lines.append("- Age: \(profile.age)")
        lines.append("- Height: \(useMetric ? String(format: "%.0f cm", profile.heightCm) : String(format: "%.1f in", profile.heightCm / 2.54))")
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
    private static func openAIToolsArray() -> [[String: Any]] {
        let dateRangeSchema: [String: Any] = [
            "type": "object",
            "properties": [
                "from": ["type": "string", "description": "ISO date yyyy-MM-dd, inclusive start"],
                "to": ["type": "string", "description": "ISO date yyyy-MM-dd, inclusive end"],
                "limit": ["type": "integer", "description": "Optional max entries to return"],
            ],
            "required": ["from", "to"],
        ]
        let summarySchema: [String: Any] = ["type": "object", "properties": [:]]

        return CoachTools.toolNames.map { name -> [String: Any] in
            [
                "type": "function",
                "function": [
                    "name": name,
                    "description": CoachTools.toolDescriptions[name] ?? "",
                    "parameters": name == "get_data_summary" ? summarySchema : dateRangeSchema,
                ],
            ]
        }
    }

    private static func callOpenAICompatible(baseURL: String, model: String, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, provider: AIProvider, tools: CoachTools) async throws -> String {
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
        if let apiKey = AIProviderSettings.currentAPIKey {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        if provider == .openrouter {
            headers["HTTP-Referer"] = "https://github.com/apoorvdarshan/fud-ai"
            headers["X-Title"] = "Fud AI"
        }

        let toolsArray = openAIToolsArray()

        for _ in 0..<maxToolRounds {
            var body: [String: Any] = [
                "model": model,
                "messages": messages,
                "tools": toolsArray,
                "tool_choice": "auto",
            ]
            body[provider.openAICompatibleTokenLimitKey(for: model)] = 1024
            let data = try await send(url: url, headers: headers, body: body)
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let choices = json["choices"] as? [[String: Any]],
                  let message = choices.first?["message"] as? [String: Any]
            else {
                throw ChatError.invalidResponse
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
    private static func anthropicToolsArray() -> [[String: Any]] {
        let dateRangeSchema: [String: Any] = [
            "type": "object",
            "properties": [
                "from": ["type": "string", "description": "ISO date yyyy-MM-dd"],
                "to": ["type": "string", "description": "ISO date yyyy-MM-dd"],
                "limit": ["type": "integer", "description": "Optional max entries"],
            ],
            "required": ["from", "to"],
        ]
        let summarySchema: [String: Any] = ["type": "object", "properties": [:]]
        return CoachTools.toolNames.map { name -> [String: Any] in
            [
                "name": name,
                "description": CoachTools.toolDescriptions[name] ?? "",
                "input_schema": name == "get_data_summary" ? summarySchema : dateRangeSchema,
            ]
        }
    }

    private static func callAnthropic(baseURL: String, model: String, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, tools: CoachTools) async throws -> String {
        guard let apiKey = AIProviderSettings.currentAPIKey else { throw ChatError.noAPIKey }
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

        let toolsArray = anthropicToolsArray()
        let headers = [
            "Content-Type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
        ]

        for _ in 0..<maxToolRounds {
            let body: [String: Any] = [
                "model": model,
                "max_tokens": 1024,
                "system": systemPrompt,
                "tools": toolsArray,
                "messages": messages,
            ]
            let data = try await send(url: url, headers: headers, body: body)
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
    private static func geminiToolsObject() -> [String: Any] {
        let dateRangeSchema: [String: Any] = [
            "type": "object",
            "properties": [
                "from": ["type": "string", "description": "ISO date yyyy-MM-dd"],
                "to": ["type": "string", "description": "ISO date yyyy-MM-dd"],
                "limit": ["type": "integer", "description": "Optional max entries"],
            ],
            "required": ["from", "to"],
        ]
        let summarySchema: [String: Any] = ["type": "object", "properties": [:]]

        let declarations: [[String: Any]] = CoachTools.toolNames.map { name in
            [
                "name": name,
                "description": CoachTools.toolDescriptions[name] ?? "",
                "parameters": name == "get_data_summary" ? summarySchema : dateRangeSchema,
            ]
        }
        return ["functionDeclarations": declarations]
    }

    private static func callGemini(baseURL: String, model: String, systemPrompt: String, history: [ChatMessage], newUserMessage: String, imageData: Data?, tools: CoachTools) async throws -> String {
        let apiKey = AIProviderSettings.currentAPIKey
        if !AIAccessSettings.isUsingFudAIPlus, apiKey == nil {
            throw ChatError.noAPIKey
        }
        guard let url = URL(string: "\(baseURL)/models/\(model):generateContent") else {
            throw ChatError.apiError("Invalid API URL.")
        }

        var contents: [[String: Any]] = []
        for msg in history {
            let role = msg.role == .user ? "user" : "model"
            contents.append(["role": role, "parts": [["text": msg.content]]])
        }
        contents.append(["role": "user", "parts": geminiUserParts(text: newUserMessage, imageData: imageData)])

        let toolsObj = geminiToolsObject()

        for _ in 0..<maxToolRounds {
            let body: [String: Any] = [
                "systemInstruction": ["parts": [["text": systemPrompt]]],
                "contents": contents,
                "tools": [toolsObj],
            ]
            let data: Data
            if AIAccessSettings.isUsingFudAIPlus {
                data = try await FudAIProxyClient.generateContent(task: .coach, body: body)
            } else {
                data = try await send(
                    url: url,
                    headers: ["Content-Type": "application/json", "X-goog-api-key": apiKey ?? ""],
                    body: body
                )
            }
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
                    responseParts.append([
                        "functionResponse": [
                            "name": name,
                            "response": ["content": resultObj],
                        ],
                    ])
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

    private static func send(url: URL, headers: [String: String], body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
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
        switch status {
        case 503, 529:
            return "The AI provider is overloaded right now. We retried a few times — please try again in a minute, or switch to a different provider/model in Settings → AI Provider."
        case 429:
            return "Rate limit hit on your API key. Wait a minute, or switch to another provider in Settings → AI Provider."
        case 401, 403:
            return "Your API key was rejected. Open Settings → AI Provider and re-paste a valid key."
        default:
            return raw
        }
    }
}
