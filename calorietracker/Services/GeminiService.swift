import Foundation
import UIKit

struct GeminiService {
    struct FoodAnalysis {
        var name: String
        var calories: Int
        var protein: Int
        var carbs: Int
        var fat: Int
    }

    struct NutritionLabelAnalysis {
        var name: String
        var caloriesPer100g: Double
        var proteinPer100g: Double
        var carbsPer100g: Double
        var fatPer100g: Double
        var servingSizeGrams: Double?

        func scaled(to grams: Double) -> FoodAnalysis {
            FoodAnalysis(
                name: name,
                calories: Int(round(caloriesPer100g * grams / 100)),
                protein: Int(round(proteinPer100g * grams / 100)),
                carbs: Int(round(carbsPer100g * grams / 100)),
                fat: Int(round(fatPer100g * grams / 100))
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

    static func autoAnalyze(image: UIImage) async throws -> FoodAnalysis {
        let prompt = """
        Analyze this image. It could be either a photo of food OR a nutrition facts label.

        If it's a food photo: identify the food and estimate nutritional content for the serving shown.
        If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

        Respond ONLY with JSON: {"name":"...","calories":0,"protein":0,"carbs":0,"fat":0}
        All integers. Calories in kcal, macros in grams.
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
          "fat": 00
        }

        All values should be integers. Calories in kcal, protein/carbs/fat in grams.
        Give your best estimate for a typical serving size shown in the image.
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
          "serving_size_grams": 00.0
        }

        All values should be numbers. If serving size is not available, use null.
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
        return FoodAnalysis(name: name, calories: calories, protein: protein, carbs: carbs, fat: fat)
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
            servingSizeGrams: servingSize
        )
    }
}
