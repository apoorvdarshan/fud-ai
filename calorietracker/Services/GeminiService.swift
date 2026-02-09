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
                return "No API key found. Please add your Gemini API key to Secrets.plist."
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

    static func analyzeTextInput(brand: String, name: String, quantity: String, unit: String) async throws -> FoodAnalysis {
        let foodDescription = brand.isEmpty ? name : "\(brand) \(name)"
        let prompt = """
        Estimate the nutritional content for:
        Food: \(foodDescription)
        Quantity: \(quantity) \(unit)
        If a brand is mentioned, use that brand's known nutritional data.
        Respond ONLY with JSON:
        {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0,"serving_size_grams":0.0,"emoji":"🍽️","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"cholesterol":0.0,"sodium":0.0,"potassium":0.0}
        Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the quantity specified. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
        Include a single food emoji that best represents the food. Use null for any nutrient you cannot estimate.
        """

        let text = try await callGeminiText(prompt: prompt)
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

        let text = try await callGemini(image: image, prompt: prompt)
        return try parseFoodAnalysis(from: text)
    }

    static func analyzeFood(image: UIImage) async throws -> FoodAnalysis {
        let prompt = """
        Analyze this food image. Identify the food and estimate its nutritional content.

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "name": "Food Name",
          "calories": 000,
          "protein": 00,
          "carbs": 00,
          "fat": 00,
          "serving_size_grams": 000.0,
          "sugar": 0.0,
          "added_sugar": 0.0,
          "fiber": 0.0,
          "saturated_fat": 0.0,
          "monounsaturated_fat": 0.0,
          "polyunsaturated_fat": 0.0,
          "cholesterol": 0.0,
          "sodium": 0.0,
          "potassium": 0.0
        }

        Calories/protein/carbs/fat are integers. serving_size_grams is the estimated weight in grams of the serving shown. Micronutrients are numbers (sugar/fiber/sat fat/mono fat/poly fat in grams, cholesterol/sodium/potassium in milligrams).
        Give your best estimate for a typical serving size shown in the image. Use null for any nutrient you cannot estimate.
        """

        let text = try await callGemini(image: image, prompt: prompt)
        return try parseFoodAnalysis(from: text)
    }

    static func analyzeNutritionLabel(image: UIImage) async throws -> NutritionLabelAnalysis {
        let prompt = """
        Read this nutrition label image. Extract the nutritional values per 100g (or per 100ml).
        If the label shows per-serving values, convert them to per-100g using the serving size.

        For the name, identify the product or brand name visible on the packaging or label.
        If no name is visible, describe the food type (e.g. "Protein Bar", "Yogurt", "Cereal").

        Respond ONLY with a JSON object in this exact format, no other text:
        {
          "name": "Product Name",
          "calories_per_100g": 000.0,
          "protein_per_100g": 00.0,
          "carbs_per_100g": 00.0,
          "fat_per_100g": 00.0,
          "serving_size_grams": 00.0,
          "sugar_per_100g": 0.0,
          "added_sugar_per_100g": 0.0,
          "fiber_per_100g": 0.0,
          "saturated_fat_per_100g": 0.0,
          "monounsaturated_fat_per_100g": 0.0,
          "polyunsaturated_fat_per_100g": 0.0,
          "cholesterol_per_100g": 0.0,
          "sodium_per_100g": 0.0,
          "potassium_per_100g": 0.0
        }

        All values should be numbers. If serving size or any nutrient is not available, use null.
        """

        let text = try await callGemini(image: image, prompt: prompt)
        return try parseNutritionLabel(from: text)
    }

    private static func callGemini(image: UIImage, prompt: String) async throws -> String {
        guard let apiKey = APIKeyManager.geminiAPIKey() else {
            throw AnalysisError.noAPIKey
        }

        guard let imageData = image.jpegData(compressionQuality: 0.8) else {
            throw AnalysisError.imageConversionFailed
        }

        let base64Image = imageData.base64EncodedString()

        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=\(apiKey)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "contents": [
                [
                    "parts": [
                        [
                            "inlineData": [
                                "mimeType": "image/jpeg",
                                "data": base64Image
                            ]
                        ],
                        [
                            "text": prompt
                        ]
                    ]
                ]
            ]
        ]

        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw AnalysisError.networkError(error)
        }

        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
            if let errorJson = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let error = errorJson["error"] as? [String: Any],
               let message = error["message"] as? String {
                throw AnalysisError.apiError(message)
            }
            throw AnalysisError.apiError("HTTP \(httpResponse.statusCode)")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let firstCandidate = candidates.first,
              let content = firstCandidate["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]],
              let text = parts.first?["text"] as? String
        else {
            throw AnalysisError.invalidResponse
        }

        return text
    }

    private static func callGeminiText(prompt: String) async throws -> String {
        guard let apiKey = APIKeyManager.geminiAPIKey() else {
            throw AnalysisError.noAPIKey
        }

        let url = URL(string: "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=\(apiKey)")!

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body: [String: Any] = [
            "contents": [
                [
                    "parts": [
                        ["text": prompt]
                    ]
                ]
            ]
        ]

        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw AnalysisError.networkError(error)
        }

        if let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode != 200 {
            if let errorJson = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let error = errorJson["error"] as? [String: Any],
               let message = error["message"] as? String {
                throw AnalysisError.apiError(message)
            }
            throw AnalysisError.apiError("HTTP \(httpResponse.statusCode)")
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = json["candidates"] as? [[String: Any]],
              let firstCandidate = candidates.first,
              let content = firstCandidate["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]],
              let text = parts.first?["text"] as? String
        else {
            throw AnalysisError.invalidResponse
        }

        return text
    }

    private static func extractJSON(from text: String) -> String {
        // Strip markdown code fences if present
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
        else {
            throw AnalysisError.invalidResponse
        }
        let emoji = json["emoji"] as? String
        let servingSizeGrams = (json["serving_size_grams"] as? NSNumber)?.doubleValue ?? 100
        return FoodAnalysis(
            name: name, calories: calories, protein: protein, carbs: carbs, fat: fat, servingSizeGrams: servingSizeGrams, emoji: emoji,
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
        else {
            throw AnalysisError.invalidResponse
        }
        let servingSize = (json["serving_size_grams"] as? NSNumber)?.doubleValue
        return NutritionLabelAnalysis(
            name: name,
            caloriesPer100g: caloriesPer100g,
            proteinPer100g: proteinPer100g,
            carbsPer100g: carbsPer100g,
            fatPer100g: fatPer100g,
            servingSizeGrams: servingSize,
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
