#if canImport(FoundationModels)
import Foundation
import FoundationModels
import UIKit

enum OnDeviceAIError: LocalizedError {
    case unavailable(String)

    var errorDescription: String? {
        switch self {
        case .unavailable(let reason):
            "Apple Intelligence is unavailable: \(reason)"
        }
    }
}

/// General text generation through Apple's on-device Foundation Models framework.
/// This is used only when the user explicitly selects Apple Intelligence as Text AI.
@available(iOS 26.0, *)
struct OnDeviceAIService {
    static var isAvailable: Bool {
        if case .available = SystemLanguageModel.default.availability {
            return true
        }
        return false
    }

    static var availabilityDescription: String {
        switch SystemLanguageModel.default.availability {
        case .available:
            "Available on this iPhone"
        case .unavailable(.deviceNotEligible):
            "This iPhone does not support Apple Intelligence"
        case .unavailable(.appleIntelligenceNotEnabled):
            "Enable Apple Intelligence in iPhone Settings"
        case .unavailable(.modelNotReady):
            "The on-device model is still downloading"
        }
    }

    static func requireAvailable() throws {
        guard isAvailable else {
            throw OnDeviceAIError.unavailable(availabilityDescription)
        }
    }

    static func respond(to prompt: String, instructions: String? = nil) async throws -> String {
        try requireAvailable()
        let session = LanguageModelSession(instructions: instructions)
        let response = try await session.respond(to: prompt)
        return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Free-form multimodal prompt. Used for nutrition labels, lab reports, and other
    /// image workflows that must keep the caller's JSON shape.
    @available(iOS 27.0, *)
    static func respond(
        to prompt: String,
        images: [UIImage],
        instructions: String? = nil
    ) async throws -> String {
        try requireAvailable()
        guard !images.isEmpty else {
            return try await respond(to: prompt, instructions: instructions)
        }
        let session = LanguageModelSession(instructions: instructions)
        let response = try await session.respond {
            prompt
            for (index, image) in images.enumerated() {
                if let cgImage = image.cgImage {
                    Attachment(cgImage)
                        .label("image-\(index)")
                } else {
                    Attachment(image)
                        .label("image-\(index)")
                }
            }
        }
        return response.content.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @available(iOS 27.0, *)
    static func respond(
        to prompt: String,
        imageDataList: [Data],
        instructions: String? = nil
    ) async throws -> String {
        let images = try imageDataList.map { data -> UIImage in
            guard let image = UIImage(data: data) else {
                throw OnDeviceAIError.unavailable("Could not read one of the photos.")
            }
            return image
        }
        return try await respond(to: prompt, images: images, instructions: instructions)
    }
}

/// On-device food analysis using Apple Intelligence (iOS 26+, iPhone 15 Pro / iPhone 16+).
/// Runs only when Apple Intelligence is explicitly selected as the Text AI provider.
@available(iOS 26.0, *)
struct OnDeviceFoodService {

    // MARK: - Structured output schema

    @Generable
    struct FoodResult {
        @Guide(description: "Short common name of the food or meal (e.g. 'Grilled chicken breast', 'Big Mac', 'Oatmeal with milk')")
        var name: String

        @Guide(description: "Total calories in kcal for the entire analyzed amount (integer)")
        var calories: Int

        @Guide(description: "Total protein in grams for the entire analyzed amount")
        var proteinGrams: Double

        @Guide(description: "Total carbohydrates in grams for the entire analyzed amount")
        var carbsGrams: Double

        @Guide(description: "Total fat in grams for the entire analyzed amount")
        var fatGrams: Double

        @Guide(description: "Total analyzed amount in grams (e.g. 150 for '150g chicken', 100 for '2 eggs')")
        var servingSizeGrams: Double

        @Guide(description: "Single food emoji that best represents this food (e.g. '🍗', '🥚', '🍔'). Empty string if no clear emoji.")
        var emoji: String

        @Guide(description: "Sugar content in grams. Use -1 if you cannot reliably estimate.")
        var sugarGrams: Double

        @Guide(description: "Dietary fiber in grams. Use -1 if you cannot reliably estimate.")
        var fiberGrams: Double

        @Guide(description: "Saturated fat in grams. Use -1 if you cannot reliably estimate.")
        var saturatedFatGrams: Double

        @Guide(description: "Sodium in milligrams. Use -1 if you cannot reliably estimate.")
        var sodiumMg: Double

        @Guide(description: "Natural serving unit label when a non-gram unit is obvious: 'piece' or 'slice' for discrete solids (pizza, cake, bread, egg, banana), 'cup' or 'ml' for liquids and volumes, 'tbsp' or 'tsp' for spooned condiments. Leave empty string when grams is the most natural unit.")
        var servingUnit: String

        @Guide(description: "How many servingUnits equal the entire analyzed amount (e.g. 2 if the user described 2 eggs). Use 0 when servingUnit is empty.")
        var servingUnitQuantity: Double

        @Guide(description: "Grams per one servingUnit (e.g. 50 if one egg weighs 50 g). Use 0 when servingUnit is empty.")
        var gramsPerUnit: Double
    }

    // MARK: - Availability

    static var isAvailable: Bool {
        OnDeviceAIService.isAvailable
    }

    // MARK: - Analysis

    private static var foodAnalysisInstructions: String {
        let userContext = AIProviderSettings.currentUserContext.map {
            "\n\nUSER-SUPPLIED CONTEXT\n\($0)"
        } ?? ""
        return """
        You are a precise nutrition database. Given a food description or meal photo(s) in any language, \
        return accurate nutritional values using these rules:

        QUANTITY PARSING
        - Parse the quantity stated or clearly implied in the description or visible in the image(s).
        - "2 eggs" means 2 × ~50 g = 100 g total; calories and all nutrients must reflect the full amount.
        - "bowl of oatmeal" implies a typical 250 g cooked serving.
        - If no quantity is given, use the most common single serving or your best estimate of the visible portion.

        BRAND NAMES
        - When a brand or product name is mentioned or visible (Big Mac, Chobani, Snickers, etc.), \
          use commonly known product values when you know them; otherwise estimate from \
          the closest generic food.

        MULTIPLE ITEMS
        - When multiple distinct foods are listed or shown, sum all nutrients into a single total.

        ACCURACY
        - Use common nutrition reference values where known.
        - Calories must be mathematically consistent: ≈ protein×4 + carbs×4 + fat×9 (±5%).
        - serving_size_grams is the total weight of the entire analyzed amount.

        UNKNOWNS
        - Use -1 for sugar, fiber, saturated fat, or sodium when you cannot estimate reliably.

        SERVING UNIT
        - Provide a natural non-gram unit only when it is obvious from context.
        - Examples: slice for pizza/bread/cake, piece for fruit/cookie/egg, cup for oatmeal/soup, \
          tbsp for peanut butter/sauces.
        - Leave servingUnit empty ("") when grams is the clearest unit.
        \(userContext)
        """
    }

    static func analyzeTextInput(description: String) async throws -> GeminiService.FoodAnalysis {
        try OnDeviceAIService.requireAvailable()
        let session = LanguageModelSession(instructions: foodAnalysisInstructions)

        let response = try await session.respond(
            to: "Provide nutrition data for: \(description)",
            generating: FoodResult.self
        )

        return buildFoodAnalysis(from: response.content)
    }

    static func analyzeImages(
        images: [UIImage],
        description: String? = nil,
        progressiveMeal: Bool = false
    ) async throws -> GeminiService.FoodAnalysis {
        try OnDeviceAIService.requireAvailable()
        guard !images.isEmpty else {
            throw OnDeviceAIError.unavailable("At least one meal photo is required.")
        }
        if #available(iOS 27.0, *) {
            return try await analyzeImagesOnIOS27(
                images: images,
                description: description,
                progressiveMeal: progressiveMeal
            )
        }
        throw OnDeviceAIError.unavailable("Food photo analysis requires iOS 27 or later.")
    }

    static func analyzeImages(
        imageDataList: [Data],
        description: String? = nil,
        progressiveMeal: Bool = false
    ) async throws -> GeminiService.FoodAnalysis {
        let images = try imageDataList.map { data -> UIImage in
            guard let image = UIImage(data: data) else {
                throw OnDeviceAIError.unavailable("Could not read one of the meal photos.")
            }
            return image
        }
        return try await analyzeImages(
            images: images,
            description: description,
            progressiveMeal: progressiveMeal
        )
    }

    @available(iOS 27.0, *)
    private static func analyzeImagesOnIOS27(
        images: [UIImage],
        description: String?,
        progressiveMeal: Bool
    ) async throws -> GeminiService.FoodAnalysis {
        let progressiveInstructions: String
        if progressiveMeal {
            progressiveInstructions = """
            These images are a chronological progressive-meal sequence in capture order.
            Compare each photo with the previous one. Return foods already present only once, and add each newly visible food into your single combined meal estimate.
            """
        } else {
            progressiveInstructions = """
            Use every attached image once. Do not double-count the same food shown from multiple angles unless they clearly show separate items to combine.
            """
        }

        let trimmedNote = description?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let noteSection = trimmedNote.isEmpty
            ? ""
            : "\n\nAdditional context from the user about this meal: \(trimmedNote)"

        let session = LanguageModelSession(
            instructions: """
            \(foodAnalysisInstructions)

            PHOTO ANALYSIS
            \(progressiveInstructions)
            Identify the food in the attached photo(s) and estimate nutrition for the full visible amount.
            Read visible scale weights and packaging labels when present; prefer those over pure visual guesses.
            """
        )

        let response = try await session.respond(generating: FoodResult.self) {
            """
            Analyze the attached meal photo(s) and return accurate nutrition data for the entire meal shown.\(noteSection)
            """
            for (index, image) in images.enumerated() {
                if let cgImage = image.cgImage {
                    Attachment(cgImage)
                        .label("image-\(index)")
                } else {
                    Attachment(image)
                        .label("image-\(index)")
                }
            }
        }

        return buildFoodAnalysis(from: response.content)
    }

    // MARK: - Build FoodAnalysis from structured result

    private static func buildFoodAnalysis(from r: FoodResult) -> GeminiService.FoodAnalysis {
        let unitOptions: [ServingUnitOption]
        if !r.servingUnit.isEmpty && r.gramsPerUnit > 0 {
            unitOptions = [ServingUnitOption(
                unit: r.servingUnit,
                gramsPerUnit: r.gramsPerUnit,
                quantity: r.servingUnitQuantity > 0 ? r.servingUnitQuantity : nil
            )]
        } else {
            unitOptions = []
        }

        let selectedOption = unitOptions.first
        let emojiValue: String? = r.emoji.isEmpty ? nil : r.emoji

        return GeminiService.FoodAnalysis(
            name: r.name,
            calories: r.calories,
            protein: r.proteinGrams,
            carbs: r.carbsGrams,
            fat: r.fatGrams,
            servingSizeGrams: r.servingSizeGrams,
            emoji: emojiValue,
            sugar: r.sugarGrams >= 0 ? r.sugarGrams : nil,
            addedSugar: nil,
            fiber: r.fiberGrams >= 0 ? r.fiberGrams : nil,
            saturatedFat: r.saturatedFatGrams >= 0 ? r.saturatedFatGrams : nil,
            monounsaturatedFat: nil,
            polyunsaturatedFat: nil,
            cholesterol: nil,
            caffeine: nil,
            sodium: r.sodiumMg >= 0 ? r.sodiumMg : nil,
            potassium: nil,
            transFat: nil,
            calcium: nil,
            iron: nil,
            magnesium: nil,
            zinc: nil,
            vitaminA: nil,
            vitaminC: nil,
            vitaminD: nil,
            vitaminB12: nil,
            vitaminE: nil,
            vitaminK: nil,
            folate: nil,
            omega3: nil,
            servingUnitOptions: unitOptions,
            selectedServingUnit: selectedOption?.unit,
            selectedServingQuantity: selectedOption.map { $0.quantity(for: r.servingSizeGrams) }
        )
    }
}
#endif
