import Foundation
import UIKit

struct GeminiService {
    struct FoodAnalysis {
        var name: String
        var calories: Int
        var protein: Int
        var carbs: Int
        var fat: Int
        var servingSizeGrams: Double
        var emoji: String?
        var sugar: Double?
        var addedSugar: Double?
        var fiber: Double?
        var saturatedFat: Double?
        var monounsaturatedFat: Double?
        var polyunsaturatedFat: Double?
        var cholesterol: Double?
        var sodium: Double?
        var potassium: Double?
    }

    struct NutritionLabelAnalysis {
        var name: String
        var caloriesPer100g: Double
        var proteinPer100g: Double
        var carbsPer100g: Double
        var fatPer100g: Double
        var servingSizeGrams: Double?
        var sugarPer100g: Double?
        var addedSugarPer100g: Double?
        var fiberPer100g: Double?
        var saturatedFatPer100g: Double?
        var monounsaturatedFatPer100g: Double?
        var polyunsaturatedFatPer100g: Double?
        var cholesterolPer100g: Double?
        var sodiumPer100g: Double?
        var potassiumPer100g: Double?

        func scaled(to grams: Double) -> FoodAnalysis {
            let scale = grams / 100
            return FoodAnalysis(
                name: name,
                calories: Int(round(caloriesPer100g * scale)),
                protein: Int(round(proteinPer100g * scale)),
                carbs: Int(round(carbsPer100g * scale)),
                fat: Int(round(fatPer100g * scale)),
                servingSizeGrams: grams,
                sugar: sugarPer100g.map { round($0 * scale * 10) / 10 },
                addedSugar: addedSugarPer100g.map { round($0 * scale * 10) / 10 },
                fiber: fiberPer100g.map { round($0 * scale * 10) / 10 },
                saturatedFat: saturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                monounsaturatedFat: monounsaturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                polyunsaturatedFat: polyunsaturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                cholesterol: cholesterolPer100g.map { round($0 * scale * 10) / 10 },
                sodium: sodiumPer100g.map { round($0 * scale * 10) / 10 },
                potassium: potassiumPer100g.map { round($0 * scale * 10) / 10 }
            )
        }
    }

    enum AnalysisError: LocalizedError {
        case noAPIKey
        case imageConversionFailed
        case networkError(Error)
        case invalidResponse
        case apiError(String)

        var errorDescription: String? {
            switch self {
            case .noAPIKey:
                return "No API key configured. Add your key in Settings → AI Provider."
            case .imageConversionFailed:
                return "Failed to process the image."
            case .networkError(let error):
                return "Network error: \(error.localizedDescription)"
            case .invalidResponse:
                return "Could not understand the AI response. Please try again."
            case .apiError(let message):
                return "API error: \(message)"
            }
        }
    }

    // MARK: - Public API (unchanged interface)

    static func analyzeTextInput(description: String) async throws -> FoodAnalysis {
        let prompt = """
        Estimate the nutritional content for: \(description)
        Parse any quantities, brands, and multiple items from the text. If a brand is mentioned, use that brand's known nutritional data. If multiple items are described, sum up the total nutrition.
        Respond ONLY with JSON:
        {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"emoji":"🍽️","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
        Calories/protein/carbs/fat are integers. serving_size_grams is the estimated total weight in grams. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
        Include a single food emoji that best represents the food. Use null for any nutrient you cannot estimate.
        """
        let text = try await callAI(prompt: prompt, image: nil)
        return try parseFoodAnalysis(from: text)
    }

    static func autoAnalyze(image: UIImage) async throws -> FoodAnalysis {
        let prompt = """
        Analyze this image. It could be either a photo of food OR a nutrition facts label.

        If it's a food photo: identify the food and estimate nutritional content for the serving shown.
        If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

        Respond ONLY with JSON:
        {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
        Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
        Use null for any nutrient you cannot estimate.
        """
        let text = try await callAI(prompt: prompt, image: image)
        return try parseFoodAnalysis(from: text)
    }

    static func analyzeFood(image: UIImage, description: String? = nil) async throws -> FoodAnalysis {
        var prompt = """
        Analyze this food image. Identify the food and estimate its nutritional content.

        Respond ONLY with a JSON object in this exact format, no other text:
        {"name":"Food Name","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}

        Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving shown. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
        Give your best estimate for a typical serving size shown in the image. Use null for any nutrient you cannot estimate.
        """

        if let description, !description.trimmingCharacters(in: .whitespaces).isEmpty {
            prompt += "\n\nAdditional context from the user about this meal: \(description)\nUse this context to improve accuracy of identification, portion size, and nutrition estimates."
        }

        let text = try await callAI(prompt: prompt, image: image)
        return try parseFoodAnalysis(from: text)
    }

    static func analyzeNutritionLabel(image: UIImage) async throws -> NutritionLabelAnalysis {
        let prompt = """
        Read this nutrition label image. Extract the nutritional values per 100g (or per 100ml).
        If the label shows per-serving values, convert them to per-100g using the serving size.

        For the name, identify the product or brand name visible on the packaging or label.
        If no name is visible, describe the food type (e.g. "Protein Bar", "Yogurt", "Cereal").

        Respond ONLY with JSON:
        {"name":"Product Name","calories_per_100g":0.0,"protein_per_100g":0.0,"carbs_per_100g":0.0,"fat_per_100g":0.0,"serving_size_grams":0.0,"sugar_per_100g":0.0,"added_sugar_per_100g":0.0,"fiber_per_100g":0.0,"saturated_fat_per_100g":0.0,"monounsaturated_fat_per_100g":0.0,"polyunsaturated_fat_per_100g":0.0,"cholesterol_per_100g":0.0,"sodium_per_100g":0.0,"potassium_per_100g":0.0}

        All values should be numbers. If serving size or any nutrient is not available, use null.
        """
        let text = try await callAI(prompt: prompt, image: image)
        return try parseNutritionLabel(from: text)
    }

    // MARK: - Unified AI Call Router

    private static func callAI(prompt: String, image: UIImage?) async throws -> String {
        let provider = AIProviderSettings.selectedProvider
        let model = AIProviderSettings.selectedModel
        let baseURL = AIProviderSettings.currentBaseURL

        if provider.requiresAPIKey {
            guard let _ = AIProviderSettings.currentAPIKey else {
                throw AnalysisError.noAPIKey
            }
        }

        var imageData: Data?
        if let image {
            guard let data = image.jpegData(compressionQuality: 0.8) else {
                throw AnalysisError.imageConversionFailed
            }
            imageData = data
        }

        switch provider.apiFormat {
        case .gemini:
            return try await callGemini(baseURL: baseURL, model: model, prompt: prompt, imageData: imageData)
        case .openaiCompatible:
            return try await callOpenAICompatible(baseURL: baseURL, model: model, prompt: prompt, imageData: imageData, provider: provider)
        case .anthropic:
            return try await callAnthropic(baseURL: baseURL, model: model, prompt: prompt, imageData: imageData)
        }
    }

    // MARK: - Gemini Format

    private static func callGemini(baseURL: String, model: String, prompt: String, imageData: Data?) async throws -> String {
        let apiKey = AIProviderSettings.currentAPIKey!
        // Send the API key in the X-goog-api-key header, not the URL query string,
        // so it doesn't end up in server logs / proxies (CodeQL: cleartext transmission).
        guard let url = URL(string: "\(baseURL)/models/\(model):generateContent") else {
            throw AnalysisError.apiError("Invalid API URL. Check your provider settings.")
        }

        var parts: [[String: Any]] = []
        if let imageData {
            parts.append([
                "inlineData": [
                    "mimeType": "image/jpeg",
                    "data": imageData.base64EncodedString()
                ]
            ])
        }
        parts.append(["text": prompt])

        let body: [String: Any] = [
            "contents": [["parts": parts]]
        ]

        let data = try await makeRequest(
            url: url,
            headers: ["Content-Type": "application/json", "X-goog-api-key": apiKey],
            body: body
        )

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let content = candidates.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]],
              let text = parts.first?["text"] as? String
        else { throw AnalysisError.invalidResponse }
        return text
    }

    // MARK: - OpenAI-Compatible Format (OpenAI, xAI, OpenRouter, Together, Groq, Ollama)

    private static func callOpenAICompatible(baseURL: String, model: String, prompt: String, imageData: Data?, provider: AIProvider) async throws -> String {
        guard let url = URL(string: "\(baseURL)/chat/completions") else {
            throw AnalysisError.apiError("Invalid API URL. Check your provider settings.")
        }

        var content: [[String: Any]] = []
        if let imageData {
            content.append([
                "type": "image_url",
                "image_url": ["url": "data:image/jpeg;base64,\(imageData.base64EncodedString())"]
            ])
        }
        content.append(["type": "text", "text": prompt])

        let body: [String: Any] = [
            "model": model,
            "messages": [["role": "user", "content": content]],
            "max_tokens": 1024,
        ]

        var headers = ["Content-Type": "application/json"]
        if let apiKey = AIProviderSettings.currentAPIKey {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        if provider == .openrouter {
            headers["HTTP-Referer"] = "https://github.com/apoorvdarshan/fud-ai"
            headers["X-Title"] = "Fud AI"
        }

        let data = try await makeRequest(url: url, headers: headers, body: body)

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = json["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let text = message["content"] as? String
        else { throw AnalysisError.invalidResponse }
        return text
    }

    // MARK: - Anthropic Format

    private static func callAnthropic(baseURL: String, model: String, prompt: String, imageData: Data?) async throws -> String {
        let apiKey = AIProviderSettings.currentAPIKey!
        guard let url = URL(string: "\(baseURL)/messages") else {
            throw AnalysisError.apiError("Invalid API URL. Check your provider settings.")
        }

        var content: [[String: Any]] = []
        if let imageData {
            content.append([
                "type": "image",
                "source": [
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": imageData.base64EncodedString()
                ]
            ])
        }
        content.append(["type": "text", "text": prompt])

        let body: [String: Any] = [
            "model": model,
            "max_tokens": 1024,
            "messages": [["role": "user", "content": content]],
        ]

        let headers = [
            "Content-Type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
        ]

        let data = try await makeRequest(url: url, headers: headers, body: body)

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let contentArray = json["content"] as? [[String: Any]],
              let text = contentArray.first?["text"] as? String
        else { throw AnalysisError.invalidResponse }
        return text
    }

    // MARK: - Network

    private static func makeRequest(url: URL, headers: [String: String], body: [String: Any]) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        // Retry transient overload responses (503/429/529) with exponential backoff: 1s, 2s, 4s.
        // The "model is currently experiencing high demand" message is Google's global throttle on
        // the Gemini model, not a per-key rate limit, so a quick retry usually succeeds.
        let retryDelaysNs: [UInt64] = [1_000_000_000, 2_000_000_000, 4_000_000_000]
        var lastError: AnalysisError = .apiError("Request failed")

        for attempt in 0...retryDelaysNs.count {
            let (data, response): (Data, URLResponse)
            do {
                (data, response) = try await URLSession.shared.data(for: request)
            } catch {
                throw AnalysisError.networkError(error)
            }

            guard let httpResponse = response as? HTTPURLResponse else { return data }

            if httpResponse.statusCode == 200 {
                return data
            }

            // Parse the API's error message once so we can surface the friendliest version.
            let parsedMessage = parseErrorMessage(from: data) ?? "HTTP \(httpResponse.statusCode)"
            lastError = .apiError(friendlyMessage(for: httpResponse.statusCode, raw: parsedMessage))

            let isRetryable = httpResponse.statusCode == 503
                           || httpResponse.statusCode == 529
                           || httpResponse.statusCode == 429
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

    // MARK: - Parsing (unchanged)

    private static func extractJSON(from text: String) -> String {
        var cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleaned.hasPrefix("```json") {
            cleaned = String(cleaned.dropFirst(7))
        } else if cleaned.hasPrefix("```") {
            cleaned = String(cleaned.dropFirst(3))
        }
        if cleaned.hasSuffix("```") {
            cleaned = String(cleaned.dropLast(3))
        }
        return cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func parseFoodAnalysis(from text: String) throws -> FoodAnalysis {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = json["name"] as? String,
              let calories = (json["calories"] as? NSNumber)?.intValue,
              let protein = (json["protein"] as? NSNumber)?.intValue,
              let carbs = (json["carbs"] as? NSNumber)?.intValue,
              let fat = (json["fat"] as? NSNumber)?.intValue
        else { throw AnalysisError.invalidResponse }
        return FoodAnalysis(
            name: name, calories: calories, protein: protein, carbs: carbs, fat: fat,
            servingSizeGrams: (json["serving_size_grams"] as? NSNumber)?.doubleValue ?? 100,
            emoji: json["emoji"] as? String,
            sugar: (json["sugar"] as? NSNumber)?.doubleValue,
            addedSugar: (json["added_sugar"] as? NSNumber)?.doubleValue,
            fiber: (json["fiber"] as? NSNumber)?.doubleValue,
            saturatedFat: (json["saturated_fat"] as? NSNumber)?.doubleValue,
            monounsaturatedFat: (json["monounsaturated_fat"] as? NSNumber)?.doubleValue,
            polyunsaturatedFat: (json["polyunsaturated_fat"] as? NSNumber)?.doubleValue,
            cholesterol: (json["cholesterol"] as? NSNumber)?.doubleValue,
            sodium: (json["sodium"] as? NSNumber)?.doubleValue,
            potassium: (json["potassium"] as? NSNumber)?.doubleValue
        )
    }

    private static func parseNutritionLabel(from text: String) throws -> NutritionLabelAnalysis {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = json["name"] as? String,
              let caloriesPer100g = (json["calories_per_100g"] as? NSNumber)?.doubleValue,
              let proteinPer100g = (json["protein_per_100g"] as? NSNumber)?.doubleValue,
              let carbsPer100g = (json["carbs_per_100g"] as? NSNumber)?.doubleValue,
              let fatPer100g = (json["fat_per_100g"] as? NSNumber)?.doubleValue
        else { throw AnalysisError.invalidResponse }
        return NutritionLabelAnalysis(
            name: name, caloriesPer100g: caloriesPer100g, proteinPer100g: proteinPer100g,
            carbsPer100g: carbsPer100g, fatPer100g: fatPer100g,
            servingSizeGrams: (json["serving_size_grams"] as? NSNumber)?.doubleValue,
            sugarPer100g: (json["sugar_per_100g"] as? NSNumber)?.doubleValue,
            addedSugarPer100g: (json["added_sugar_per_100g"] as? NSNumber)?.doubleValue,
            fiberPer100g: (json["fiber_per_100g"] as? NSNumber)?.doubleValue,
            saturatedFatPer100g: (json["saturated_fat_per_100g"] as? NSNumber)?.doubleValue,
            monounsaturatedFatPer100g: (json["monounsaturated_fat_per_100g"] as? NSNumber)?.doubleValue,
            polyunsaturatedFatPer100g: (json["polyunsaturated_fat_per_100g"] as? NSNumber)?.doubleValue,
            cholesterolPer100g: (json["cholesterol_per_100g"] as? NSNumber)?.doubleValue,
            sodiumPer100g: (json["sodium_per_100g"] as? NSNumber)?.doubleValue,
            potassiumPer100g: (json["potassium_per_100g"] as? NSNumber)?.doubleValue
        )
    }
}
