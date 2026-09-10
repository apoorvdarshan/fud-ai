import Foundation
import CoreFoundation
import UIKit

struct GeminiService {
    struct FoodAnalysis {
        var name: String
        var calories: Int
        var protein: Double
        var carbs: Double
        var fat: Double
        var servingSizeGrams: Double
        var emoji: String?
        var sugar: Double?
        var addedSugar: Double?
        var fiber: Double?
        var saturatedFat: Double?
        var monounsaturatedFat: Double?
        var polyunsaturatedFat: Double?
        var cholesterol: Double?
        var caffeine: Double?
        var supplementalNutrients: [String: Double] = [:]
        var sodium: Double?
        var potassium: Double?
        var transFat: Double?
        var calcium: Double?
        var iron: Double?
        var magnesium: Double?
        var zinc: Double?
        var vitaminA: Double?
        var vitaminC: Double?
        var vitaminD: Double?
        var vitaminB12: Double?
        var vitaminE: Double?
        var vitaminK: Double?
        var folate: Double?
        var omega3: Double?
        var servingUnitOptions: [ServingUnitOption] = []
        var selectedServingUnit: String?
        var selectedServingQuantity: Double?
        /// False only for a restored/legacy log whose nutrition totals are
        /// known but whose original food mass was never recoverable.
        var servingSizeIsKnown = true
        var requiresServingUnitFallback = false
        var progressiveMeal = false
        var ingredients: [MealIngredient] = []
        var productMetadata: FoodProductMetadata? = nil
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
        var caffeinePer100g: Double?
        var supplementalNutrientsPer100g: [String: Double] = [:]
        var sodiumPer100g: Double?
        var potassiumPer100g: Double?
        var transFatPer100g: Double?
        var calciumPer100g: Double?
        var ironPer100g: Double?
        var magnesiumPer100g: Double?
        var zincPer100g: Double?
        var vitaminAPer100g: Double?
        var vitaminCPer100g: Double?
        var vitaminDPer100g: Double?
        var vitaminB12Per100g: Double?
        var vitaminEPer100g: Double?
        var vitaminKPer100g: Double?
        var folatePer100g: Double?
        var omega3Per100g: Double?
        var servingUnitOptions: [ServingUnitOption] = []
        var requiresServingUnitFallback = false

        func scaled(to grams: Double) -> FoodAnalysis {
            let selectedOption = servingUnitOptions.first
            let scale = grams / 100
            return FoodAnalysis(
                name: name,
                calories: Int(round(caloriesPer100g * scale)),
                protein: proteinPer100g * scale,
                carbs: carbsPer100g * scale,
                fat: fatPer100g * scale,
                servingSizeGrams: grams,
                sugar: sugarPer100g.map { round($0 * scale * 10) / 10 },
                addedSugar: addedSugarPer100g.map { round($0 * scale * 10) / 10 },
                fiber: fiberPer100g.map { round($0 * scale * 10) / 10 },
                saturatedFat: saturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                monounsaturatedFat: monounsaturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                polyunsaturatedFat: polyunsaturatedFatPer100g.map { round($0 * scale * 10) / 10 },
                cholesterol: cholesterolPer100g.map { round($0 * scale * 10) / 10 },
                caffeine: caffeinePer100g.map { round($0 * scale * 10) / 10 },
                supplementalNutrients: supplementalNutrientsPer100g.mapValues { round($0 * scale * 10) / 10 },
                sodium: sodiumPer100g.map { round($0 * scale * 10) / 10 },
                potassium: potassiumPer100g.map { round($0 * scale * 10) / 10 },
                transFat: transFatPer100g.map { round($0 * scale * 10) / 10 },
                calcium: calciumPer100g.map { round($0 * scale * 10) / 10 },
                iron: ironPer100g.map { round($0 * scale * 10) / 10 },
                magnesium: magnesiumPer100g.map { round($0 * scale * 10) / 10 },
                zinc: zincPer100g.map { round($0 * scale * 10) / 10 },
                vitaminA: vitaminAPer100g.map { round($0 * scale * 10) / 10 },
                vitaminC: vitaminCPer100g.map { round($0 * scale * 10) / 10 },
                vitaminD: vitaminDPer100g.map { round($0 * scale * 10) / 10 },
                vitaminB12: vitaminB12Per100g.map { round($0 * scale * 10) / 10 },
                vitaminE: vitaminEPer100g.map { round($0 * scale * 10) / 10 },
                vitaminK: vitaminKPer100g.map { round($0 * scale * 10) / 10 },
                folate: folatePer100g.map { round($0 * scale * 10) / 10 },
                omega3: omega3Per100g.map { round($0 * scale * 10) / 10 },
                servingUnitOptions: servingUnitOptions,
                selectedServingUnit: selectedOption?.unit,
                selectedServingQuantity: selectedOption?.quantity(for: grams)
            )
        }
    }

    /// AI-computed daily targets returned by `calculateGoals`.
    struct GoalCalculation {
        var calories: Int
        var protein: Int
        var carbs: Int
        var fat: Int
        var reason: String?
    }

    private struct MacroTotals {
        var calories: Int
        var protein: Double
        var carbs: Double
        var fat: Double

        static let zero = MacroTotals(calories: 0, protein: 0, carbs: 0, fat: 0)

        static func + (lhs: MacroTotals, rhs: MacroTotals) -> MacroTotals {
            MacroTotals(
                calories: lhs.calories + rhs.calories,
                protein: lhs.protein + rhs.protein,
                carbs: lhs.carbs + rhs.carbs,
                fat: lhs.fat + rhs.fat
            )
        }
    }

    enum AnalysisError: LocalizedError {
        case noAPIKey
        case imageConversionFailed
        case networkError(Error)
        case invalidResponse
        case apiError(String)
        case requestFailed(AIErrorKind)

        var errorDescription: String? {
            switch self {
            case .noAPIKey:
                return AIErrorKind.noKey.message
            case .imageConversionFailed:
                return AIErrorKind.imageConversion.message
            case .networkError(let error):
                return AIErrorKind.network(error).message
            case .invalidResponse:
                return AIErrorKind.invalidResponse.message
            case .apiError(let message):
                return AIErrorKind.classify(status: 0, raw: message).message
            case .requestFailed(let kind):
                return kind.message
            }
        }
    }

    static func analysisErrorMessage(_ error: Error) -> String {
        if error is AnalysisError || error is AnalysisFallbackError || error is Gemma4LocalModelManager.LocalModelError {
            return error.localizedDescription
        }
        if (error as NSError).domain == NSURLErrorDomain { return AIErrorKind.network(error).message }
        return AIErrorKind.generic.message
    }

    private static let foodAnalysisJSONShape = """
    {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"emoji":"🍽️","sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"trans_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
    """

    private static let foodAnalysisJSONShapeWithoutEmoji = """
    {"name":"...","calories":0,"protein":0.0,"carbs":0.0,"fat":0.0,"serving_size_grams":0.0,"sugar":0.0,"added_sugar":0.0,"fiber":0.0,"saturated_fat":0.0,"monounsaturated_fat":0.0,"polyunsaturated_fat":0.0,"trans_fat":0.0,"cholesterol":0.0,"caffeine":0.0,"creatine":0.0,"beta_alanine":0.0,"l_citrulline":0.0,"l_carnitine":0.0,"l_arginine":0.0,"taurine":0.0,"betaine":0.0,"hmb":0.0,"sodium":0.0,"potassium":0.0,"calcium":0.0,"iron":0.0,"magnesium":0.0,"zinc":0.0,"vitamin_a":0.0,"vitamin_c":0.0,"vitamin_d":0.0,"vitamin_b12":0.0,"vitamin_e":0.0,"vitamin_k":0.0,"folate":0.0,"omega_3":0.0,"ingredients":[],"unit_options":[]}
    """

    private static let nutritionLabelJSONShape = """
    {"name":"Product Name","calories_per_100g":0.0,"protein_per_100g":0.0,"carbs_per_100g":0.0,"fat_per_100g":0.0,"serving_size_grams":0.0,"sugar_per_100g":0.0,"added_sugar_per_100g":0.0,"fiber_per_100g":0.0,"saturated_fat_per_100g":0.0,"monounsaturated_fat_per_100g":0.0,"polyunsaturated_fat_per_100g":0.0,"trans_fat_per_100g":0.0,"cholesterol_per_100g":0.0,"caffeine_per_100g":0.0,"creatine_per_100g":0.0,"beta_alanine_per_100g":0.0,"l_citrulline_per_100g":0.0,"l_carnitine_per_100g":0.0,"l_arginine_per_100g":0.0,"taurine_per_100g":0.0,"betaine_per_100g":0.0,"hmb_per_100g":0.0,"sodium_per_100g":0.0,"potassium_per_100g":0.0,"calcium_per_100g":0.0,"iron_per_100g":0.0,"magnesium_per_100g":0.0,"zinc_per_100g":0.0,"vitamin_a_per_100g":0.0,"vitamin_c_per_100g":0.0,"vitamin_d_per_100g":0.0,"vitamin_b12_per_100g":0.0,"vitamin_e_per_100g":0.0,"vitamin_k_per_100g":0.0,"folate_per_100g":0.0,"omega_3_per_100g":0.0,"unit_options":[]}
    """

    private static let nutrientUnitsInstruction = "Calories are integers. Protein/carbs/fat are decimal gram values when needed. serving_size_grams is the estimated weight in grams. Nutrients are numbers: sugar/fiber/fats/omega_3/creatine/beta_alanine/l_citrulline/l_carnitine/l_arginine/taurine/betaine/hmb in grams; cholesterol/caffeine/sodium/potassium/calcium/iron/magnesium/zinc/vitamin_c/vitamin_e in milligrams; vitamin_a/vitamin_d/vitamin_b12/vitamin_k/folate in micrograms. Only report sports-nutrition compounds when explicitly present in a label or description; otherwise use 0."

    private static let servingUnitOptionsInstruction = """
    unit_options is required and must always be a JSON array. Each item must be a complete object with this exact schema (the values are schema examples only; never copy them):
    {"unit":"slice","quantity":2.0,"grams_per_unit":60.0}
    quantity is the number of units in the whole analyzed amount, and grams_per_unit is the grams in one unit. For every item, quantity * grams_per_unit must approximately equal serving_size_grams. Do not include g/gram/grams as an option.
    Return [] when there is no reliable non-gram unit. An empty array is a complete, valid answer.
    Never invent a count from the food name or total grams. Only return a countable unit when its quantity is stated in the user's text, visible in the image or label, or strongly implied by the described or visible analyzed portion. Do not assume quantity is 1 merely because the food is commonly sold or served as one piece.
    """

    private static let ingredientBreakdownInstruction = """
    ingredients is required. For a meal with multiple meaningful foods, return each food once using this exact object shape: {"name":"...","grams":0.0,"calories":0,"protein":0.0,"carbs":0.0,"fat":0.0}. Ingredient grams and macros must describe the analyzed amount and add up approximately to the meal totals. Return [] for a nutrition label, a single simple food, or when a reliable breakdown is not possible.
    """

    // MARK: - Public API (unchanged interface)

    static func suggestMealWhatIf(
        entry: FoodEntry,
        dayEntries: [FoodEntry],
        profile: UserProfile,
        weightMetric: Bool
    ) async throws -> String {
        let current = macroTotals(for: dayEntries)
        let meal = macroTotals(for: entry)
        let after = current + meal
        let goals = MacroTotals(
            calories: profile.effectiveCalories,
            protein: Double(profile.effectiveProtein),
            carbs: Double(profile.effectiveCarbs),
            fat: Double(profile.effectiveFat)
        )
        let remaining = MacroTotals(
            calories: goals.calories - after.calories,
            protein: goals.protein - after.protein,
            carbs: goals.carbs - after.carbs,
            fat: goals.fat - after.fat
        )
        let existingMeals = dayEntries.isEmpty
            ? "No meals logged yet for this day."
            : dayEntries
                .prefix(12)
                .map { "- \($0.name): \($0.calories) kcal, \(formatGrams($0.protein))g protein, \(formatGrams($0.carbs))g carbs, \(formatGrams($0.fat))g fat" }
                .joined(separator: "\n")
        let weight = weightMetric
            ? String(format: "%.1f kg", profile.weightKg)
            : String(format: "%.1f lb", profile.weightKg * 2.20462)
        let bodyFat = profile.bodyFatPercentage.map { "\(Int(($0 * 100).rounded()))%" } ?? "not set"

        let prompt = """
        You are a concise nutrition coach inside Fud AI. The user is reviewing a meal before logging it.
        Analyze this what-if scenario only. Do not say the meal has already been logged. Do not change the user's goals.

        Return 2-4 short plain-English sentences, no markdown and no bullets.
        Say whether logging this meal as-is fits the remaining daily targets. If it does not fit well, suggest one practical action: reduce quantity, replace an item, add a protein/fiber source, or balance the next meal. Be specific with calories/macros from the data below.

        User:
        - Goal: \(profile.goal.displayName)
        - Activity: \(profile.activityLevel.displayName)
        - Weight: \(weight)
        - Body fat: \(bodyFat)

        Daily targets:
        \(macroLine(goals))

        Already logged today:
        \(macroLine(current))

        Meal being reviewed:
        - \(entry.name): \(macroLine(meal))

        If logged, daily total becomes:
        \(macroLine(after))

        Remaining after logging (negative means over target):
        \(macroLine(remaining))

        Existing meals today:
        \(existingMeals)
        """

        let text = try await callAI(prompt: prompt, image: nil)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func analyzeWorkout(description: String, date: Date, unit: WeightUnit,
                               library: [ExerciseLibraryItem]) async throws -> WorkoutTextDraft {
        guard !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, description.count <= 16000 else {
            throw WorkoutTextError.invalid("The workout conversation is too long. Please start over.")
        }
        let searchResponse = try await callAI(prompt: WorkoutTextDraft.searchPrompt(description: description), image: nil)
        let queries = WorkoutTextDraft.searchQueries(searchResponse, fallback: description)
        let prompt = WorkoutTextDraft.prompt(description: description, selectedDate: date, unit: unit, library: library, searchQueries: queries)
        return try WorkoutTextDraft.parse(try await callAI(prompt: prompt, image: nil), library: library)
    }

    static func analyzeTextInput(description: String) async throws -> FoodAnalysis {
        let prompt = """
        Estimate the nutritional content for: \(description)
        Parse any quantities, brands, and multiple items from the text. If a brand is mentioned, use that brand's known nutritional data. If multiple items are described, sum up the total nutrition.
        Respond ONLY with JSON:
        \(Self.foodAnalysisJSONShape)
        \(Self.nutrientUnitsInstruction)
        \(Self.servingUnitOptionsInstruction)
        \(Self.ingredientBreakdownInstruction)
        When supported by the text, use slice/piece for discrete foods, ml/cup/fl oz for liquids, tbsp/tsp for spooned foods, and can/packet for packaged foods.
        Include a single food emoji that best represents the food. Use null for any nutrient you cannot estimate.
        """
        let analysis = try await callTextFoodAnalysis(prompt: prompt, description: description)
        return await addingFallbackServingUnits(to: analysis, image: nil, description: description)
    }

    static func autoAnalyze(image: UIImage) async throws -> FoodAnalysis {
        let prompt = """
        Analyze this image. It could be either a photo of food OR a nutrition facts label.

        If it's a food photo: identify the food and estimate nutritional content for the serving shown.
        If it's a nutrition label: read the values and calculate for one serving size as listed on the label.

        Respond ONLY with JSON:
        \(Self.foodAnalysisJSONShapeWithoutEmoji)
        \(Self.nutrientUnitsInstruction)
        \(Self.servingUnitOptionsInstruction)
        \(Self.ingredientBreakdownInstruction)
        When supported by the image or label, use slice/piece for discrete foods, ml/cup/fl oz for liquids, tbsp/tsp for spooned foods, and can/packet for packaged foods. For a whole or mostly-whole divisible food, count only clearly visible pieces or slices and derive grams_per_unit from serving_size_grams / quantity.
        Use null for any nutrient you cannot estimate.
        """
        let text = try await callAI(prompt: prompt, image: image)
        let analysis = try parseFoodAnalysis(from: text)
        return await addingFallbackServingUnits(to: analysis, image: image, description: nil)
    }

    static func analyzeFood(image: UIImage, description: String? = nil) async throws -> FoodAnalysis {
        var prompt = """
        Analyze this food image. Identify the food and estimate its nutritional content.

        Respond ONLY with a JSON object in this exact format, no other text:
        \(Self.foodAnalysisJSONShapeWithoutEmoji)

        \(Self.nutrientUnitsInstruction)
        \(Self.servingUnitOptionsInstruction)
        \(Self.ingredientBreakdownInstruction)
        When supported by the image, use slice/piece for discrete foods, ml/cup/fl oz for liquids, tbsp/tsp for spooned foods, and can/packet for packaged foods. For a whole or mostly-whole divisible food, count only clearly visible pieces or slices and derive grams_per_unit from serving_size_grams / quantity.
        Give your best estimate for the visible food amount shown in the image. For whole/mostly-whole cakes, pizzas, pies, loaves, or similar foods, estimate the total visible item/remaining item weight rather than defaulting to one slice. Use null for any nutrient you cannot estimate.
        """

        if let description, !description.trimmingCharacters(in: .whitespaces).isEmpty {
            prompt += "\n\nAdditional context from the user about this meal: \(description)\nUse this context to improve accuracy of identification, portion size, and nutrition estimates."
        }

        let text = try await callAI(prompt: prompt, image: image)
        let analysis = try parseFoodAnalysis(from: text)
        return await addingFallbackServingUnits(to: analysis, image: image, description: description)
    }

    static func multiPhotoAnalysisPrompt(progressiveMeal: Bool, description: String? = nil) -> String {
        let interpretation: String
        if progressiveMeal {
            interpretation = """
            These images are a chronological progressive-meal sequence in the exact order the user captured or selected them.
            - Photo 1 shows the first ingredient on the plate. Each later photo shows the same plate after one or more new ingredients were added.
            - Compare each photo with the previous photo. Return foods already present only once, and add each newly visible food as its own ingredient.
            - When the photos show reliable cumulative scale totals with the same plate and tare, the first ingredient weight is the first reading. Each later added weight is the current scale total minus the previous scale total.
            - If the scale was visibly tared or reset before a photo, use that photo's reading directly for the newly added ingredient.
            - Never subtract unreadable, incompatible, or decreasing readings. In that case estimate only the newly added food from the visual change and user context.
            - The final meal weight should match the latest reliable cumulative reading, and ingredient weights and macros should add up approximately to the meal totals.
            """
        } else {
            interpretation = """
            Use every image once. Do not double-count the same food shown from multiple angles. When separate ingredients are shown, combine their nutrition into one meal total. Read visible scale weights and nutrition labels when available; prefer those measurements over visual portion estimates.
            Treat the photos as multiple views of the same item unless there are clearly separate foods.
            """
        }

        var prompt = """
        Analyze these food-related images together as one meal logging request. They may show different angles of the same food, separate ingredients, kitchen-scale readings, packaging, or nutrition labels.

        \(interpretation)

        Respond ONLY with a JSON object in this exact format, no other text:
        \(Self.foodAnalysisJSONShapeWithoutEmoji)

        \(Self.nutrientUnitsInstruction)
        \(Self.servingUnitOptionsInstruction)
        \(Self.ingredientBreakdownInstruction)
        When supported by the images or label, use slice/piece for discrete foods, ml/cup/fl oz for liquids, tbsp/tsp for spooned foods, and can/packet/bar for packaged foods.
        Give your best estimate for the actual amount shown or implied across the images. Use null for any nutrient you cannot estimate.
        """

        if let description, !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            prompt += "\n\nAdditional context from the user about this complete meal: \(description)\nApply this note to the full image set."
        }
        return prompt
    }

    static func analyzeFood(
        images: [UIImage],
        description: String? = nil,
        progressiveMeal: Bool = false
    ) async throws -> FoodAnalysis {
        guard !images.isEmpty else { throw AnalysisError.imageConversionFailed }

        let prompt = multiPhotoAnalysisPrompt(progressiveMeal: progressiveMeal, description: description)

        let text = try await callAI(prompt: prompt, images: images)
        let analysis = try parseFoodAnalysis(from: text)
        var result = await addingFallbackServingUnits(to: analysis, image: images[0], description: description)
        result.progressiveMeal = progressiveMeal
        return result
    }

    static func analyzeNutritionLabel(image: UIImage) async throws -> NutritionLabelAnalysis {
        let prompt = """
        Read this nutrition label image. Extract the nutritional values per 100g (or per 100ml).
        If the label shows per-serving values, convert them to per-100g using the serving size.

        For the name, identify the product or brand name visible on the packaging or label.
        If no name is visible, describe the food type (e.g. "Protein Bar", "Yogurt", "Cereal").

        Respond ONLY with JSON:
        \(Self.nutritionLabelJSONShape)

        \(Self.servingUnitOptionsInstruction)
        All nutrient and serving-size values should be numbers. If serving size or any nutrient is not available, use null. Only include a label serving unit such as slice, piece, tbsp, cup, ml, fl oz, can, or packet when its quantity is actually printed or otherwise visible on the label.
        """
        let text = try await callAI(prompt: prompt, image: image)
        let analysis = try parseNutritionLabel(from: text)
        return await addingFallbackServingUnits(to: analysis, image: image)
    }

    /// Extracts clearly positive/elevated sensitizations from an ISAC/ALEX-style allergy lab report image.
    /// Returns plain common food/allergen names (not component codes). Empty if none found.
    static func extractAllergensFromLabReport(image: UIImage) async throws -> [String] {
        try await extractAllergensFromLabReport(images: [image])
    }

    static func extractAllergensFromLabReport(images: [UIImage]) async throws -> [String] {
        guard !images.isEmpty else { throw AnalysisError.imageConversionFailed }
        let prompt = """
        This image is an allergy blood-test lab report (ISAC, ALEX, or similar multiplex IgE / component-resolved diagnostics).
        Extract ONLY clearly positive or elevated sensitizations.
        Map each to a plain common food or allergen name (e.g. "milk", "peanut", "egg", "wheat", "shrimp") — NOT component codes like Ara h 2, Gal d 1, or ISAC allergen codes.
        If nothing is clearly positive/elevated, return an empty array.
        Do not invent allergens. Do not claim anything is safe.
        Respond ONLY with JSON:
        {"allergens":["milk","peanut"]}
        """
        let text = try await callAI(prompt: prompt, images: images)
        return try parseAllergensFromLabReport(from: text)
    }

    static func suggestOptionalNutrientGoals(
        profile: UserProfile,
        currentGoals: OptionalNutrientGoals,
        heightMetric: Bool,
        weightMetric: Bool
    ) async throws -> OptionalNutrientGoals {
        let weight = weightMetric
            ? String(format: "%.1f kg", profile.weightKg)
            : String(format: "%.1f lb", profile.weightKg * 2.20462)
        let height = heightMetric
            ? String(format: "%.0f cm", profile.heightCm)
            : String(format: "%.1f in", profile.heightCm / 2.54)
        let canonicalWeight = String(format: "%.1f kg", profile.weightKg)
        let canonicalHeight = String(format: "%.1f cm", profile.heightCm)
        let bodyFat = profile.bodyFatPercentage.map {
            "\(Int(($0 * 100).rounded()))% (fraction \(String(format: "%.3f", $0)))"
        } ?? "not set"
        let goalWeight = profile.goalWeightKg.map { kg in
            let preferred = weightMetric
                ? String(format: "%.1f kg", kg)
                : String(format: "%.1f lb", kg * 2.20462)
            return "\(String(format: "%.1f kg", kg)) (preferred display: \(preferred))"
        } ?? "not set"
        let currentGoalLines = OptionalNutrient.allCases
            .map { "- \($0.displayName): \(currentGoals.goal(for: $0)) \($0.unit) (\($0.goalStyle))" }
            .joined(separator: "\n")

        let prompt = """
        You are setting daily non-macro nutrient goals for a food tracking app.
        Return ONLY valid JSON with these exact numeric keys:
        {"fiber":30,"sugar":50,"added_sugar":25,"saturated_fat":20,"cholesterol":300,"caffeine":400,"creatine":0,"beta_alanine":0,"l_citrulline":0,"l_carnitine":0,"l_arginine":0,"taurine":0,"betaine":0,"hmb":0,"sodium":2300,"potassium":3500,"trans_fat":0,"calcium":1000,"iron":18,"magnesium":400,"zinc":11,"vitamin_a":900,"vitamin_c":90,"vitamin_d":20,"vitamin_b12":3,"vitamin_e":15,"vitamin_k":120,"folate":400,"omega_3":2}

        Do not include calories, protein, carbs, or fat. Do not change calorie or macro targets.
        Use reasonable general-adult nutrition targets unless the user's profile strongly suggests a small adjustment.
        Treat fiber, potassium, calcium, iron, magnesium, zinc, vitamins, folate, and omega-3 as target/minimum style goals. Treat sugar, added sugar, saturated fat, trans fat, cholesterol, caffeine, and sodium as daily limit-style goals.
        Keep creatine, beta-alanine, L-citrulline, L-carnitine, L-arginine, taurine, betaine, and HMB at 0 unless the profile explicitly requests a custom target.
        Units: sugar, added_sugar, fiber, saturated_fat, trans_fat, and omega_3 are grams; cholesterol, caffeine, sodium, potassium, calcium, iron, magnesium, zinc, vitamin_c, and vitamin_e are milligrams; vitamin_a, vitamin_d, vitamin_b12, vitamin_k, and folate are micrograms.
        Keep values in normal consumer-tracker ranges and round to practical app-friendly numbers.
        Use integers only.

        User profile:
        - Gender: \(profile.gender.displayName)
        - Age: \(profile.age)
        - Height: \(canonicalHeight) (preferred display: \(height))
        - Weight: \(canonicalWeight) (preferred display: \(weight))
        - Activity: \(profile.activityLevel.displayName)
        - Weight goal: \(profile.goal.displayName)
        - Goal weight: \(goalWeight)
        - Body fat: \(bodyFat)
        - Current calorie target: \(profile.effectiveCalories) kcal
        - Current macro targets: \(profile.effectiveProtein)g protein, \(profile.effectiveCarbs)g carbs, \(profile.effectiveFat)g fat

        Current non-macro nutrient defaults/custom values:
        \(currentGoalLines)
        """

        let text = try await callAI(prompt: prompt, image: nil)
        return try parseOptionalNutrientGoals(from: text, fallback: currentGoals)
    }

    // MARK: - AI Goal Calculation

    /// AI-driven daily target calculation. Sends the app's full formula set, the user's
    /// profile / goals / settings plus a completeness-aware, privacy-safe evidence pack, so the
    /// model can use longer-term real signals without treating partial diary days as true intake.
    /// Routes through the
    /// user's selected provider. If AI is unavailable, callers preserve the
    /// existing saved targets. ONLY for goal targets — does not touch food estimation.
    static func calculateGoals(
        profile: UserProfile,
        measuredTdee: Int? = nil,
        measurement: BodyMeasurement? = nil,
        evidence: GoalEvidence? = nil,
        heightMetric: Bool,
        weightMetric: Bool
    ) async throws -> GoalCalculation {
        let weight = weightMetric
            ? String(format: "%.1f kg", profile.weightKg)
            : String(format: "%.1f lb", profile.weightKg * 2.20462)
        let height = heightMetric
            ? String(format: "%.0f cm", profile.heightCm)
            : String(format: "%.1f in", profile.heightCm / 2.54)
        let canonicalWeight = String(format: "%.1f kg", profile.weightKg)
        let canonicalHeight = String(format: "%.1f cm", profile.heightCm)
        let bodyFat = profile.bodyFatPercentage.map {
            "\(Int(($0 * 100).rounded()))% (fraction \(String(format: "%.3f", $0)))"
        } ?? "not set"
        let goalWeight = profile.goalWeightKg.map { kg in
            let preferred = weightMetric
                ? String(format: "%.1f kg", kg)
                : String(format: "%.1f lb", kg * 2.20462)
            return "\(String(format: "%.1f kg", kg)) (preferred display: \(preferred))"
        } ?? "not set"
        let weekly = profile.weeklyChangeKg.map { String(format: "%.2f kg/week", $0) } ?? "not set (maintain)"
        let bmrMethod = profile.usesBodyFatForBMR ? "Katch-McArdle (automatic because body fat is known)" : "Mifflin-St Jeor"

        // Energy Burn toggle: when the user has it on (and Apple Health has enough data), this is
        // their REAL measured maintenance and replaces the formula TDEE as the calorie anchor.
        let measuredSection: String
        if let measuredTdee {
            measuredSection = "\nENERGY BURN MAINTENANCE ANCHOR — \(measuredTdee) kcal/day, derived from the recent Apple Health energy window. It uses measured total energy when at least 3 total-energy days exist; otherwise it combines average measured active energy with formula BMR. Prefer this over formula TDEE, apply the goal/weekly adjustment, and sanity-check against complete-diary and weight trends."
        } else {
            measuredSection = ""
        }

        // Optional tape-measure circumferences + derived metrics. Extra signal only — never overrides
        // the formulas. A shrinking waist alongside flat/declining weight implies recomposition.
        let measurementsSection: String
        if evidence != nil {
            // An evidence pack owns recency. When it has no recent measurements, do not silently
            // fall back to an undated value that may be years old.
            measurementsSection = ""
        } else if let summary = measurement?.promptSummary(gender: profile.gender, heightCm: profile.heightCm) {
            measurementsSection = "\nBODY MEASUREMENTS — the user's latest tape-measure circumferences and the metrics derived from them. Use as extra signal: a shrinking waist with steady or falling weight suggests recomposition, so keep protein high and don't over-cut. Treat the US-Navy body-fat figure as a rough estimate, not exact.\n\(summary)"
        } else {
            measurementsSection = ""
        }

        let evidenceSection = evidence.map {
            "\n\($0.promptSection(profile: profile))\nUse the confidence and completeness labels explicitly. Prefer likely-complete days and measured trends; never interpret missing or likely-partial days as true low intake."
        } ?? ""

        let prompt = """
        You are the goal calculator for a calorie & macro tracking app. Using the FORMULAS, USER PROFILE, and GOAL EVIDENCE below, compute the user's daily targets.
        Return ONLY valid JSON with these exact keys (integers, plus a short reason):
        {"calories":2000,"protein":150,"carbs":200,"fat":60,"reason":"Short reason under 100 characters"}

        Use the app's formulas as the basis. Use empirical signals only according to the evidence confidence/completeness labels; never infer low intake from partial or missing diary days.
        FORMULAS
        - BMR (Mifflin-St Jeor): base = 10*weightKg + 6.25*heightCm - 5*age - 161; if male add 166; female/other use base.
        - BMR (Katch-McArdle, used automatically when body fat is known): 370 + 21.6 * (1 - bodyFatFraction) * weightKg.
        - TDEE = BMR * activity multiplier. Multipliers: sedentary 1.2, light 1.375, moderate 1.465, active 1.55, very active 1.725, extra active 1.9.
        - Calorie target = TDEE + adjustment. adjustment = 0 for maintain; lose: -(weeklyChangeKg*7700/7); gain: +(weeklyChangeKg*7700/7).
        - Guarded empirical maintenance: for a matching 14/28/90-day window, maintenance ≈ average likely-complete intake − (weightChangeKg × 7700 ÷ weightSpanDays). Use only when evidence confidence is medium/high, at least half the window is likely-complete, there are at least 2 weigh-ins spanning 14+ days, and the implied trend is physiologically plausible. Never use partial/missing intake, never divide by the nominal window when the reported weight span differs, and ignore this estimate when those guards fail. Priority: measured Energy Burn anchor when available; otherwise a well-supported empirical estimate; otherwise formula TDEE.
        - Protein: aim NEAR the formula protein value shown below — these activity rates are full-bodyweight equivalents (sedentary 0.8, light 1.2, moderate 1.6, active 1.8, very active 2.0, extra active 2.2 g/kg of full bodyweight; +0.2 if losing). You may choose a value within about ±15% of it based on the weight goal and the observed history (lean toward the higher end during a calorie deficit to preserve muscle). Do NOT reinterpret these rates as lean-mass rates or scale protein down merely to fit a lower calorie target.
        - Fat: 0.6 g/kg of full bodyweight.
        - Carbs: the calories remaining after protein (4 kcal/g) and fat (9 kcal/g), divided by 4. Keep 4*protein + 4*carbs + 9*fat approximately equal to calories.
        BMR method in effect for this user: \(bmrMethod).
        Keep calories within 800-6000. Use integers only. Output no keys other than calories, protein, carbs, fat, reason.

        USER PROFILE
        - Gender: \(profile.gender.displayName)
        - Age: \(profile.age)
        - Height: \(canonicalHeight) (preferred display: \(height))
        - Weight: \(canonicalWeight) (preferred display: \(weight))
        - Body fat: \(bodyFat)
        - Activity level: \(profile.activityLevel.displayName)
        - Weight goal: \(profile.goal.displayName)
        - Weekly change preference: \(weekly)
        - Goal weight: \(goalWeight)

        APP FORMULA REFERENCE (already computed deterministically — use as the anchor)
        - BMR: \(Int(profile.bmr.rounded())) kcal/day
        - TDEE: \(Int(profile.tdee.rounded())) kcal/day
        - Formula calorie target: \(profile.dailyCalories) kcal/day
        - Formula macros: \(profile.proteinGoal) g protein, \(profile.carbsGoal) g carbs, \(profile.fatGoal) g fat

        CURRENT SAVED TARGETS (before this recalculation)
        - Calories: \(profile.effectiveCalories) kcal/day
        - Protein: \(profile.effectiveProtein) g/day
        - Carbs: \(profile.effectiveCarbs) g/day
        - Fat: \(profile.effectiveFat) g/day
        \(measuredSection)
        \(measurementsSection)
        \(evidenceSection)
        """

        let text = try await callAI(prompt: prompt, image: nil)
        return try parseGoalCalculation(from: text, profile: profile)
    }

    // MARK: - Weight Forecast Insight

    /// Asks the user's selected LLM to summarize their weight trend and suggest 2–3 adjustments
    /// in plain English. Caller provides an already-computed WeightForecast so the LLM gets hard
    /// numbers instead of guessing.
    static func analyzeWeightTrend(
        profile: UserProfile,
        forecast: WeightForecast,
        recentAvgMacros: (protein: Int, carbs: Int, fat: Int)?,
        heightMetric: Bool,
        weightMetric: Bool
    ) async throws -> String {
        let unit = weightMetric ? "kg" : "lbs"
        let wUnit: (Double) -> String = { kg in
            weightMetric ? String(format: "%.1f kg", kg) : String(format: "%.1f lbs", kg * 2.20462)
        }
        let weekly: (Double) -> String = { kg in
            weightMetric ? String(format: "%+.2f kg/week", kg) : String(format: "%+.2f lbs/week", kg * 2.20462)
        }

        var lines: [String] = []
        lines.append("User profile:")
        lines.append("- Gender: \(profile.gender.rawValue)")
        lines.append("- Age: \(profile.age)")
        lines.append("- Height: \(heightMetric ? String(format: "%.0f cm", profile.heightCm) : String(format: "%.1f in", profile.heightCm / 2.54))")
        lines.append("- Current weight: \(wUnit(forecast.currentWeightKg))")
        lines.append("- Activity level: \(profile.activityLevel.displayName)")
        lines.append("- Goal: \(profile.goal.displayName)")
        if let goal = profile.goalWeightKg {
            lines.append("- Goal weight: \(wUnit(goal))")
        }
        if let bf = profile.bodyFatPercentage {
            lines.append("- Body fat: \(Int(bf * 100))%")
        }
        lines.append("")
        lines.append("Energy balance (from \(forecast.daysOfFoodData) days of logged food):")
        lines.append("- Avg daily intake: \(forecast.avgDailyCalories) kcal")
        lines.append("- TDEE estimate: \(forecast.tdee) kcal")
        lines.append("- Daily balance: \(forecast.dailyEnergyBalance >= 0 ? "+" : "")\(forecast.dailyEnergyBalance) kcal")
        if let macros = recentAvgMacros {
            lines.append("- Avg macros: \(macros.protein)g protein, \(macros.carbs)g carbs, \(macros.fat)g fat")
        }
        lines.append("")
        lines.append("Projection:")
        lines.append("- Predicted (from diet): \(weekly(forecast.predictedWeeklyChangeKg))")
        if let observed = forecast.observedWeeklyChangeKg {
            lines.append("- Observed (from \(forecast.weightEntriesUsed) weight entries): \(weekly(observed))")
        }
        lines.append("- Expected weight in 30 days: \(wUnit(forecast.predictedWeight30dKg))")
        lines.append("- Expected weight in 90 days: \(wUnit(forecast.predictedWeight90dKg))")
        if let days = forecast.daysToGoal {
            lines.append("- At current pace, reach goal in ~\(days) days")
        }
        if forecast.trendsDisagree {
            lines.append("- NOTE: predicted and observed trends differ by >0.3 kg/week (possibly under-logging food).")
        }

        let prompt = """
        You are a nutrition coach analyzing a user's weight trend. Write 3–4 short sentences (plain English, no bullets, no markdown, no bold) that:
        1. State the predicted weight in \(unit) 30 days out and whether they're on track for their goal.
        2. Give one or two specific, actionable suggestions (e.g. calorie target, protein amount, activity change) grounded in the numbers below.
        3. If predicted and observed trends disagree, mention possible under-logging briefly.
        Be direct, factual, and encouraging. Do not exceed 100 words.

        \(lines.joined(separator: "\n"))
        """
        return try await callAI(prompt: prompt, image: nil)
    }

    private static func macroTotals(for entries: [FoodEntry]) -> MacroTotals {
        entries.reduce(.zero) { totals, entry in
            totals + macroTotals(for: entry)
        }
    }

    private static func macroTotals(for entry: FoodEntry) -> MacroTotals {
        MacroTotals(
            calories: entry.calories,
            protein: entry.protein,
            carbs: entry.carbs,
            fat: entry.fat
        )
    }

    private static func macroLine(_ totals: MacroTotals) -> String {
        "\(totals.calories) kcal, \(formatGrams(totals.protein))g protein, \(formatGrams(totals.carbs))g carbs, \(formatGrams(totals.fat))g fat"
    }

    private static func formatGrams(_ value: Double) -> String {
        if abs(value.rounded() - value) < 0.0001 {
            return String(Int(value.rounded()))
        }
        return String(format: "%.1f", value)
    }

    // MARK: - Unified AI Call Router

    private static func callAI(prompt: String, image: UIImage?) async throws -> String {
        try await callAI(prompt: prompt, images: image.map { [$0] } ?? [])
    }

    private static func callTextFoodAnalysis(prompt: String, description: String) async throws -> FoodAnalysis {
        let primary = AIProviderSettings.currentConfig(requiresVision: false)
        if primary.provider.requiresAPIKey, primary.apiKey == nil {
            throw AnalysisError.noAPIKey
        }

        do {
            return try await dispatchFoodAnalysis(
                provider: primary.provider,
                model: primary.model,
                baseURL: primary.baseURL,
                apiKey: primary.apiKey,
                prompt: prompt,
                description: description
            )
        } catch {
            if error is CancellationError { throw error }
            guard let fallback = AIProviderSettings.currentTextFallbackConfig(
                excludingPrimary: primary.provider,
                model: primary.model
            ) else {
                throw error
            }
            do {
                return try await dispatchFoodAnalysis(
                    provider: fallback.provider,
                    model: fallback.model,
                    baseURL: fallback.baseURL,
                    apiKey: fallback.apiKey,
                    prompt: prompt,
                    description: description
                )
            } catch let fallbackError {
                if fallbackError is CancellationError { throw fallbackError }
                let chosenError = AIRequestErrorPolicy.errorToSurface(
                    primaryProvider: primary.provider, primaryError: error, fallbackError: fallbackError
                )
                let detail = (chosenError as? AnalysisError)?.localizedDescription
                    ?? (primary.provider == .gemma4Local ? chosenError.localizedDescription : AIErrorKind.generic.message)
                throw AnalysisFallbackError(primaryName: primary.provider.displayName,
                    fallbackName: fallback.provider.displayName, detail: detail)
            }
        }
    }

    private static func dispatchFoodAnalysis(
        provider: AIProvider,
        model: String,
        baseURL: String,
        apiKey: String?,
        prompt: String,
        description: String
    ) async throws -> FoodAnalysis {
        if provider == .appleIntelligence {
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) {
                return try await OnDeviceFoodService.analyzeTextInput(description: description)
            }
            #endif
            throw AnalysisError.requestFailed(.unsupportedDevice)
        }

        let text = try await dispatch(
            provider: provider,
            model: model,
            baseURL: baseURL,
            apiKey: apiKey,
            prompt: prompt,
            imageDataList: []
        )
        return try parseFoodAnalysis(from: text)
    }

    private static func callAI(prompt: String, images: [UIImage]) async throws -> String {
        let primary = AIProviderSettings.currentConfig(requiresVision: !images.isEmpty)
        if primary.provider.requiresAPIKey, primary.apiKey == nil {
            throw AnalysisError.noAPIKey
        }

        let imageDataList = try images.map {
            try encodedJPEGData(for: $0)
        }

        do {
            return try await dispatch(
                provider: primary.provider,
                model: primary.model,
                baseURL: primary.baseURL,
                apiKey: primary.apiKey,
                prompt: prompt,
                imageDataList: imageDataList
            )
        } catch {
            if error is CancellationError { throw error }
            // imageConversionFailed is local — fallback won't help, rethrow.
            // For everything else (network / 5xx / 4xx / parser failure) try fallback.
            if case AnalysisError.imageConversionFailed = error { throw error }
            let fallback = images.isEmpty
                ? AIProviderSettings.currentTextFallbackConfig(
                    excludingPrimary: primary.provider,
                    model: primary.model
                )
                : AIProviderSettings.currentImageFallbackConfig(
                    excludingPrimary: primary.provider,
                    model: primary.model
                )
            guard let fallback else {
                throw error
            }
            do {
                return try await dispatch(
                    provider: fallback.provider,
                    model: fallback.model,
                    baseURL: fallback.baseURL,
                    apiKey: fallback.apiKey,
                    prompt: prompt,
                    imageDataList: imageDataList
                )
            } catch let fallbackError {
                if fallbackError is CancellationError { throw fallbackError }
                let chosenError = AIRequestErrorPolicy.errorToSurface(
                    primaryProvider: primary.provider, primaryError: error, fallbackError: fallbackError
                )
                let detail = (chosenError as? AnalysisError)?.localizedDescription
                    ?? (primary.provider == .gemma4Local ? chosenError.localizedDescription : AIErrorKind.generic.message)
                throw AnalysisFallbackError(primaryName: primary.provider.displayName,
                    fallbackName: fallback.provider.displayName, detail: detail)
            }
        }
    }

    static func encodedJPEGData(for image: UIImage, maxDimension: CGFloat = 1_600) throws -> Data {
        let pixelWidth = image.size.width * image.scale
        let pixelHeight = image.size.height * image.scale
        guard pixelWidth > 0, pixelHeight > 0 else {
            throw AnalysisError.imageConversionFailed
        }

        let longestSide = max(pixelWidth, pixelHeight)
        let scale = min(1, maxDimension / longestSide)
        let targetSize = CGSize(
            width: max(1, (pixelWidth * scale).rounded()),
            height: max(1, (pixelHeight * scale).rounded())
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let uploadImage = UIGraphicsImageRenderer(size: targetSize, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }

        guard let data = uploadImage.jpegData(compressionQuality: 0.8) else {
            throw AnalysisError.imageConversionFailed
        }
        return data
    }

    private static func dispatch(provider: AIProvider, model: String, baseURL: String, apiKey: String?, prompt: String, imageDataList: [Data]) async throws -> String {
        switch provider.apiFormat {
        case .onDevice:
            guard imageDataList.isEmpty else {
                throw AnalysisError.requestFailed(.textOnly)
            }
            #if canImport(FoundationModels)
            if #available(iOS 26.0, *) {
                return try await OnDeviceAIService.respond(
                    to: prompt,
                    instructions: AIProviderSettings.currentUserContext
                )
            }
            #endif
            throw AnalysisError.requestFailed(.unsupportedDevice)
        case .liteRTLocal:
            return try await Gemma4LocalModelManager.shared.generate(
                prompt: prompt,
                images: imageDataList,
                systemPrompt: AIProviderSettings.currentUserContext,
                maxOutputTokens: AIProviderSettings.maxResponseTokens
            )
        case .gemini:
            guard let key = apiKey else { throw AnalysisError.noAPIKey }
            return try await callGemini(baseURL: baseURL, model: model, apiKey: key, prompt: prompt, imageDataList: imageDataList)
        case .openaiCompatible:
            return try await callOpenAICompatible(baseURL: baseURL, model: model, apiKey: apiKey, provider: provider, prompt: prompt, imageDataList: imageDataList)
        case .anthropic:
            guard let key = apiKey else { throw AnalysisError.noAPIKey }
            return try await callAnthropic(baseURL: baseURL, model: model, apiKey: key, prompt: prompt, imageDataList: imageDataList)
        }
    }

    // MARK: - Gemini Format

    private static func callGemini(baseURL: String, model: String, apiKey: String?, prompt: String, imageDataList: [Data]) async throws -> String {
        // Send the API key in the X-goog-api-key header, not the URL query string,
        // so it doesn't end up in server logs / proxies (CodeQL: cleartext transmission).
        var parts: [[String: Any]] = []
        for imageData in imageDataList {
            parts.append([
                "inlineData": [
                    "mimeType": "image/jpeg",
                    "data": imageData.base64EncodedString()
                ]
            ])
        }
        parts.append(["text": prompt])

        var body: [String: Any] = [
            "contents": [["parts": parts]]
        ]
        if let userContext = AIProviderSettings.currentUserContext {
            body["systemInstruction"] = ["parts": [["text": userContext]]]
        }

        guard let apiKey else { throw AnalysisError.noAPIKey }
        guard let url = URL(string: "\(baseURL)/models/\(model):generateContent") else {
            throw AnalysisError.requestFailed(.invalidURL)
        }
        let data = try await makeRequest(
            url: url,
            headers: ["Content-Type": "application/json", "X-goog-api-key": apiKey],
            body: body,
            provider: .gemini
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

    struct OpenAITextResponse {
        let text: String?
        let finishReason: String?
        let hasReasoning: Bool

        var wasTruncated: Bool { finishReason == "length" }
        var needsCompactRetry: Bool { wasTruncated || (text == nil && hasReasoning) }
    }

    static func parseOpenAITextResponse(from data: Data) throws -> OpenAITextResponse {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw AnalysisError.invalidResponse }

        let errorMessage = (json["error"] as? [String: Any])?["message"] as? String
        guard let choice = (json["choices"] as? [[String: Any]])?.first else {
            if let errorMessage, !errorMessage.isEmpty { throw AnalysisError.apiError(errorMessage) }
            throw AnalysisError.invalidResponse
        }
        let finishReason = choice["finish_reason"] as? String
        if finishReason == "error" {
            throw AnalysisError.apiError(errorMessage ?? "The AI provider returned an error.")
        }
        guard let message = choice["message"] as? [String: Any] else {
            throw AnalysisError.invalidResponse
        }

        let text: String?
        if let string = message["content"] as? String {
            let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
            text = trimmed.isEmpty ? nil : trimmed
        } else if let blocks = message["content"] as? [[String: Any]] {
            let joined = blocks
                .compactMap { ($0["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            text = joined.isEmpty ? nil : joined
        } else {
            text = nil
        }

        let hasReasoning = !(message["reasoning"] as? String ?? "").isEmpty
            || !(message["reasoning_content"] as? String ?? "").isEmpty
            || !((message["reasoning_details"] as? [[String: Any]]) ?? []).isEmpty
        return OpenAITextResponse(text: text, finishReason: finishReason, hasReasoning: hasReasoning)
    }

    private static func compactOpenAIRetryPrompt(_ prompt: String) -> String {
        """
        \(prompt)

        IMPORTANT: The previous response did not contain a complete answer. Return only the requested compact JSON object, with no reasoning, explanation, or markdown. Keep the complete response under \(AIProviderSettings.maxResponseTokens) tokens.
        """
    }

    private static func callOpenAICompatible(baseURL: String, model: String, apiKey: String?, provider: AIProvider, prompt: String, imageDataList: [Data]) async throws -> String {
        guard let url = URL(string: "\(baseURL)/chat/completions") else {
            throw AnalysisError.requestFailed(.invalidURL)
        }

        var headers = ["Content-Type": "application/json"]
        if let apiKey {
            headers["Authorization"] = "Bearer \(apiKey)"
        }
        if provider == .openrouter {
            headers["HTTP-Referer"] = "https://github.com/apoorvdarshan/fud-ai"
            headers["X-Title"] = "Fud AI"
        }

        func request(_ requestPrompt: String, compactRetry: Bool) async throws -> OpenAITextResponse {
            var content: [[String: Any]] = imageDataList.map { imageData in
                [
                    "type": "image_url",
                    "image_url": ["url": "data:image/jpeg;base64,\(imageData.base64EncodedString())"],
                ]
            }
            content.append(["type": "text", "text": requestPrompt])

            var messages: [[String: Any]] = []
            if let userContext = AIProviderSettings.currentUserContext {
                messages.append(["role": "system", "content": userContext])
            }
            messages.append(["role": "user", "content": content])

            var body: [String: Any] = [
                "model": model,
                "messages": messages,
            ]
            body[provider.openAICompatibleTokenLimitKey(for: model)] = AIProviderSettings.maxResponseTokens
            if provider == .openrouter {
                body["reasoning"] = AIProviderSettings.openRouterReasoningEffort.requestOptions(compactRetry: compactRetry, exclude: true)
            }
            let data = try await makeRequest(url: url, headers: headers, body: body, provider: provider)
            return try parseOpenAITextResponse(from: data)
        }

        var response = try await request(prompt, compactRetry: false)
        if response.needsCompactRetry {
            response = try await request(compactOpenAIRetryPrompt(prompt), compactRetry: true)
            if response.wasTruncated {
                throw AnalysisError.requestFailed(.truncated)
            }
        }
        guard let text = response.text else { throw AnalysisError.invalidResponse }
        return text
    }

    // MARK: - Anthropic Format

    struct AnthropicTextResponse {
        let text: String?
        let stopReason: String?

        var wasTruncated: Bool { stopReason == "max_tokens" }
    }

    static func parseAnthropicTextResponse(from data: Data) throws -> AnthropicTextResponse {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let content = json["content"] as? [[String: Any]]
        else { throw AnalysisError.invalidResponse }

        let text = content
            .filter { ($0["type"] as? String) == "text" }
            .compactMap { ($0["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")

        return AnthropicTextResponse(
            text: text.isEmpty ? nil : text,
            stopReason: json["stop_reason"] as? String
        )
    }

    private static func compactAnthropicRetryPrompt(_ prompt: String) -> String {
        """
        \(prompt)

        IMPORTANT: The previous response was truncated. Return only the requested compact JSON object, with no reasoning, explanation, or markdown. Keep the complete response under \(AIProviderSettings.maxResponseTokens) tokens.
        """
    }

    private static func callAnthropic(baseURL: String, model: String, apiKey: String, prompt: String, imageDataList: [Data]) async throws -> String {
        guard let url = URL(string: "\(baseURL)/messages") else {
            throw AnalysisError.requestFailed(.invalidURL)
        }

        let headers = [
            "Content-Type": "application/json",
            "x-api-key": apiKey,
            "anthropic-version": "2023-06-01",
        ]

        func request(_ requestPrompt: String) async throws -> AnthropicTextResponse {
            var content: [[String: Any]] = imageDataList.map { imageData in
                [
                    "type": "image",
                    "source": [
                        "type": "base64",
                        "media_type": "image/jpeg",
                        "data": imageData.base64EncodedString(),
                    ],
                ]
            }
            content.append(["type": "text", "text": requestPrompt])

            var body: [String: Any] = [
                "model": model,
                "max_tokens": AIProviderSettings.maxResponseTokens,
                "messages": [["role": "user", "content": content]],
            ]
            if let userContext = AIProviderSettings.currentUserContext {
                body["system"] = userContext
            }
            let data = try await makeRequest(url: url, headers: headers, body: body, provider: .anthropic)
            return try parseAnthropicTextResponse(from: data)
        }

        var response = try await request(prompt)
        if response.wasTruncated {
            response = try await request(compactAnthropicRetryPrompt(prompt))
            if response.wasTruncated {
                throw AnalysisError.requestFailed(.truncated)
            }
        }
        guard let text = response.text else { throw AnalysisError.invalidResponse }
        return text
    }

    // MARK: - Network

    static func makeRequest(
        url: URL,
        headers: [String: String],
        body: [String: Any],
        provider: AIProvider,
        session: URLSession = .shared
    ) async throws -> Data {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        if let timeout = AIProviderSettings.requestTimeout(for: provider) {
            request.timeoutInterval = timeout
        }
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
                (data, response) = try await session.data(for: request)
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as URLError where error.code == .cancelled {
                throw CancellationError()
            } catch {
                throw AnalysisError.networkError(error)
            }

            guard let httpResponse = response as? HTTPURLResponse else { return data }

            if httpResponse.statusCode == 200 {
                return data
            }

            // Parse the API's error message once so we can surface the friendliest version.
            // Fall back to a status-code-only message when parsing finds nothing OR when the
            // parsed value is empty (some providers return `{"error": {"message": ""}}`,
            // which used to slip through as a literal blank "API error: " alert).
            let parsed = parseErrorMessage(from: data) ?? ""
            let parsedMessage = parsed.isEmpty ? "HTTP \(httpResponse.statusCode)" : parsed
            lastError = .requestFailed(AIErrorKind.classify(status: httpResponse.statusCode, raw: parsedMessage + "\n" + (String(data: data, encoding: .utf8) ?? "")))

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

    // MARK: - Parsing (unchanged)

    private static func extractJSON(from text: String) -> String {
        var cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)

        if let openFence = cleaned.range(of: "```json", options: .caseInsensitive)
            ?? cleaned.range(of: "```") {
            cleaned = String(cleaned[openFence.upperBound...])
            if let closeFence = cleaned.range(of: "```", options: .backwards) {
                cleaned = String(cleaned[..<closeFence.lowerBound])
            }
        }
        cleaned = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)

        guard let firstBrace = cleaned.firstIndex(of: "{") else { return cleaned }
        var depth = 0
        var inString = false
        var escape = false
        var endIndex: String.Index?
        for idx in cleaned[firstBrace...].indices {
            let ch = cleaned[idx]
            if escape { escape = false; continue }
            if ch == "\\" { escape = true; continue }
            if ch == "\"" { inString.toggle(); continue }
            if inString { continue }
            if ch == "{" { depth += 1 }
            else if ch == "}" {
                depth -= 1
                if depth == 0 {
                    endIndex = cleaned.index(after: idx)
                    break
                }
            }
        }
        if let end = endIndex {
            return String(cleaned[firstBrace..<end])
        }
        return cleaned
    }

    static func parseFoodAnalysis(from text: String) throws -> FoodAnalysis {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = json["name"] as? String,
              let calories = (json["calories"] as? NSNumber)?.intValue,
              let protein = (json["protein"] as? NSNumber)?.doubleValue,
              let carbs = (json["carbs"] as? NSNumber)?.doubleValue,
              let fat = (json["fat"] as? NSNumber)?.doubleValue
        else { throw AnalysisError.invalidResponse }
        let responseServingSizeGrams = (json["serving_size_grams"] as? NSNumber)?.doubleValue
        let servingSizeGrams = responseServingSizeGrams ?? 1
        let parsedUnitOptions = parseInitialServingUnitOptions(
            from: json,
            servingSizeGrams: responseServingSizeGrams
        )
        let selectedOption = parsedUnitOptions.options.first
        func double(_ key: String) -> Double? {
            (json[key] as? NSNumber)?.doubleValue
        }
        let supplementalNutrients = Dictionary(uniqueKeysWithValues: SupplementalNutrient.allCases.compactMap { nutrient in
            double(nutrient.jsonKey).map { (nutrient.rawValue, $0) }
        })
        let ingredients = (json["ingredients"] as? [[String: Any]] ?? [])
            .prefix(20)
            .compactMap { item -> MealIngredient? in
                guard let name = (item["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !name.isEmpty,
                      let grams = (item["grams"] as? NSNumber)?.doubleValue,
                      grams > 0,
                      let calories = (item["calories"] as? NSNumber)?.doubleValue,
                      let protein = (item["protein"] as? NSNumber)?.doubleValue,
                      let carbs = (item["carbs"] as? NSNumber)?.doubleValue,
                      let fat = (item["fat"] as? NSNumber)?.doubleValue,
                      [calories, protein, carbs, fat].allSatisfy({ $0 >= 0 })
                else { return nil }
                return MealIngredient(
                    name: name,
                    grams: grams,
                    calories: Int(round(calories)),
                    protein: protein,
                    carbs: carbs,
                    fat: fat
                )
            }
        return FoodAnalysis(
            name: name, calories: calories, protein: protein, carbs: carbs, fat: fat,
            servingSizeGrams: servingSizeGrams,
            emoji: json["emoji"] as? String,
            sugar: double("sugar"),
            addedSugar: double("added_sugar"),
            fiber: double("fiber"),
            saturatedFat: double("saturated_fat"),
            monounsaturatedFat: double("monounsaturated_fat"),
            polyunsaturatedFat: double("polyunsaturated_fat"),
            cholesterol: double("cholesterol"),
            caffeine: double("caffeine"),
            supplementalNutrients: supplementalNutrients,
            sodium: double("sodium"),
            potassium: double("potassium"),
            transFat: double("trans_fat"),
            calcium: double("calcium"),
            iron: double("iron"),
            magnesium: double("magnesium"),
            zinc: double("zinc"),
            vitaminA: double("vitamin_a"),
            vitaminC: double("vitamin_c"),
            vitaminD: double("vitamin_d"),
            vitaminB12: double("vitamin_b12"),
            vitaminE: double("vitamin_e"),
            vitaminK: double("vitamin_k"),
            folate: double("folate"),
            omega3: double("omega_3"),
            servingUnitOptions: parsedUnitOptions.options,
            selectedServingUnit: responseServingSizeGrams == nil ? "serving" : selectedOption?.unit,
            selectedServingQuantity: responseServingSizeGrams == nil ? 1 : selectedOption?.quantity(for: servingSizeGrams),
            servingSizeIsKnown: responseServingSizeGrams != nil,
            requiresServingUnitFallback: parsedUnitOptions.requiresFallback,
            ingredients: ingredients
        )
    }

    static func parseAllergensFromLabReport(from text: String) throws -> [String] {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let raw = json["allergens"] as? [Any]
        else { throw AnalysisError.invalidResponse }

        var seen = Set<String>()
        return raw.compactMap { item -> String? in
            let name: String
            if let string = item as? String {
                name = string
            } else if let number = item as? NSNumber {
                name = number.stringValue
            } else {
                return nil
            }
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, seen.insert(trimmed.lowercased()).inserted else { return nil }
            return trimmed
        }
    }

    static func parseNutritionLabel(from text: String) throws -> NutritionLabelAnalysis {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = json["name"] as? String,
              let caloriesPer100g = (json["calories_per_100g"] as? NSNumber)?.doubleValue,
              let proteinPer100g = (json["protein_per_100g"] as? NSNumber)?.doubleValue,
              let carbsPer100g = (json["carbs_per_100g"] as? NSNumber)?.doubleValue,
              let fatPer100g = (json["fat_per_100g"] as? NSNumber)?.doubleValue
        else { throw AnalysisError.invalidResponse }
        let servingSizeGrams = (json["serving_size_grams"] as? NSNumber)?.doubleValue
        let parsedUnitOptions = parseInitialServingUnitOptions(
            from: json,
            servingSizeGrams: servingSizeGrams
        )
        func double(_ key: String) -> Double? {
            (json[key] as? NSNumber)?.doubleValue
        }
        let supplementalNutrientsPer100g = Dictionary(uniqueKeysWithValues: SupplementalNutrient.allCases.compactMap { nutrient in
            double("\(nutrient.jsonKey)_per_100g").map { (nutrient.rawValue, $0) }
        })
        return NutritionLabelAnalysis(
            name: name, caloriesPer100g: caloriesPer100g, proteinPer100g: proteinPer100g,
            carbsPer100g: carbsPer100g, fatPer100g: fatPer100g,
            servingSizeGrams: servingSizeGrams,
            sugarPer100g: double("sugar_per_100g"),
            addedSugarPer100g: double("added_sugar_per_100g"),
            fiberPer100g: double("fiber_per_100g"),
            saturatedFatPer100g: double("saturated_fat_per_100g"),
            monounsaturatedFatPer100g: double("monounsaturated_fat_per_100g"),
            polyunsaturatedFatPer100g: double("polyunsaturated_fat_per_100g"),
            cholesterolPer100g: double("cholesterol_per_100g"),
            caffeinePer100g: double("caffeine_per_100g"),
            supplementalNutrientsPer100g: supplementalNutrientsPer100g,
            sodiumPer100g: double("sodium_per_100g"),
            potassiumPer100g: double("potassium_per_100g"),
            transFatPer100g: double("trans_fat_per_100g"),
            calciumPer100g: double("calcium_per_100g"),
            ironPer100g: double("iron_per_100g"),
            magnesiumPer100g: double("magnesium_per_100g"),
            zincPer100g: double("zinc_per_100g"),
            vitaminAPer100g: double("vitamin_a_per_100g"),
            vitaminCPer100g: double("vitamin_c_per_100g"),
            vitaminDPer100g: double("vitamin_d_per_100g"),
            vitaminB12Per100g: double("vitamin_b12_per_100g"),
            vitaminEPer100g: double("vitamin_e_per_100g"),
            vitaminKPer100g: double("vitamin_k_per_100g"),
            folatePer100g: double("folate_per_100g"),
            omega3Per100g: double("omega_3_per_100g"),
            servingUnitOptions: parsedUnitOptions.options,
            requiresServingUnitFallback: parsedUnitOptions.requiresFallback
        )
    }

    private static func parseOptionalNutrientGoals(
        from text: String,
        fallback: OptionalNutrientGoals
    ) throws -> OptionalNutrientGoals {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw AnalysisError.invalidResponse }

        var goals = fallback.mergedWithDefaults()
        var parsedAnyValue = false

        for nutrient in OptionalNutrient.allCases {
            let rawValue = json[nutrient.jsonKey] ?? json[nutrient.rawValue]
            guard let number = rawValue as? NSNumber else { continue }
            goals.setGoal(number.intValue, for: nutrient)
            parsedAnyValue = true
        }

        guard parsedAnyValue else { throw AnalysisError.invalidResponse }
        return goals.mergedWithDefaults()
    }

    static func parseGoalCalculation(from text: String, profile: UserProfile? = nil) throws -> GoalCalculation {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawCalories = strictJSONNumber(json["calories"]),
              let rawProtein = strictJSONNumber(json["protein"]),
              let rawCarbs = strictJSONNumber(json["carbs"]),
              let rawFat = strictJSONNumber(json["fat"])
        else { throw AnalysisError.invalidResponse }

        // Bad provider output must leave the user's existing targets untouched. Do not silently
        // turn negative, zero-macro, extreme, or internally incoherent JSON into a plausible plan.
        guard (800...6_000).contains(rawCalories),
              (10...500).contains(rawProtein),
              (0...1_500).contains(rawCarbs),
              (10...400).contains(rawFat)
        else { throw AnalysisError.invalidResponse }
        let reportedMacroCalories = rawProtein * 4 + rawCarbs * 4 + rawFat * 9
        let allowedMismatch = max(100, rawCalories * 0.15)
        guard abs(reportedMacroCalories - rawCalories) <= allowedMismatch else {
            throw AnalysisError.invalidResponse
        }

        let calories = Int(rawCalories.rounded())
        var protein = min(Int(rawProtein.rounded()), calories / 4)

        // Exact 4/4/9 consistency requires fat grams to have the same modulo-4
        // remainder as calories (9 kcal/g is congruent to 1 modulo 4). If an AI
        // returns impossible P/F values, preserve protein first, reduce it only
        // enough to leave room for a valid fat value, then choose the closest
        // safe fat amount and derive carbs from the remaining calories.
        let requiredFatRemainder = calories % 4
        while protein > 0, (calories - protein * 4) / 9 < requiredFatRemainder {
            protein -= 1
        }
        let maximumFat = min(400, max(0, (calories - protein * 4) / 9))
        let requestedFat = min(Int(rawFat.rounded()), maximumFat)
        let fat = closestFat(
            to: requestedFat,
            maximum: maximumFat,
            requiredRemainder: requiredFatRemainder
        )
        let carbs = max(0, (calories - protein * 4 - fat * 9) / 4)
        guard protein >= 10, fat >= 10 else { throw AnalysisError.invalidResponse }
        if let profile {
            let proteinReference = max(10, profile.proteinGoal)
            let fatReference = max(10, profile.fatGoal)
            guard protein >= Int((Double(proteinReference) * 0.70).rounded()),
                  protein <= Int((Double(proteinReference) * 1.30).rounded()),
                  fat >= Int((Double(fatReference) * 0.50).rounded()),
                  fat <= Int((Double(fatReference) * 1.50).rounded())
            else { throw AnalysisError.invalidResponse }
            let calorieReference = min(max(profile.dailyCalories, 800), 6_000)
            guard calories >= max(800, Int((Double(calorieReference) * 0.50).rounded())),
                  calories <= min(6_000, Int((Double(calorieReference) * 2.00).rounded()))
            else { throw AnalysisError.invalidResponse }
        }

        return GoalCalculation(
            calories: calories,
            protein: protein,
            // With calories clamped to 6,000 this is inherently 0...1,500.
            // Do not apply a lower independent cap: that would break 4/4/9 equality.
            carbs: carbs,
            fat: fat,
            reason: json["reason"] as? String
        )
    }

    private static func closestFat(to requested: Int, maximum: Int, requiredRemainder: Int) -> Int {
        guard maximum >= requiredRemainder else { return 0 }
        let lower: Int
        if requested >= requiredRemainder {
            lower = requiredRemainder + ((requested - requiredRemainder) / 4) * 4
        } else {
            lower = requiredRemainder
        }
        let upper = lower + 4
        if upper <= maximum, abs(upper - requested) < abs(lower - requested) {
            return upper
        }
        return min(lower, maximum)
    }

    private static func addingFallbackServingUnits(
        to analysis: FoodAnalysis,
        image: UIImage?,
        description: String?
    ) async -> FoodAnalysis {
        guard analysis.requiresServingUnitFallback else { return analysis }
        var updated = analysis
        updated.requiresServingUnitFallback = false
        guard let options = try? await inferServingUnitOptions(
            name: analysis.name,
            servingSizeGrams: analysis.servingSizeGrams,
            image: image,
            description: description
        ), !options.isEmpty else {
            return updated
        }

        updated.servingUnitOptions = options
        updated.selectedServingUnit = options.first?.unit
        updated.selectedServingQuantity = options.first?.quantity(for: analysis.servingSizeGrams)
        return updated
    }

    private static func addingFallbackServingUnits(
        to analysis: NutritionLabelAnalysis,
        image: UIImage
    ) async -> NutritionLabelAnalysis {
        guard analysis.requiresServingUnitFallback else { return analysis }
        var updated = analysis
        updated.requiresServingUnitFallback = false
        guard let servingSizeGrams = analysis.servingSizeGrams,
              let options = try? await inferServingUnitOptions(
                name: analysis.name,
                servingSizeGrams: servingSizeGrams,
                image: image,
                description: nil
              ), !options.isEmpty else {
            return updated
        }

        updated.servingUnitOptions = options
        return updated
    }

    private static func inferServingUnitOptions(
        name: String,
        servingSizeGrams: Double,
        image: UIImage?,
        description: String?
    ) async throws -> [ServingUnitOption] {
        let context = description?.trimmingCharacters(in: .whitespacesAndNewlines)
        let contextLine = context.map { "\nUser context: \($0)" } ?? ""
        let prompt = """
        The previous food analysis omitted unit_options or returned it in a malformed format. Recover non-gram serving unit options for the same food and amount only when the source provides enough evidence.

        Food: \(name)
        Total grams for the analyzed amount: \(String(format: "%.1f", servingSizeGrams))\(contextLine)

        Return ONLY JSON:
        {"unit_options":[]}

        \(Self.servingUnitOptionsInstruction)

        Rules:
        - For countable portions, use slice or piece only when the count is stated in the user context, clearly visible in the image, or strongly implied by wording such as "a slice" or "one bar".
        - A food name, typical package, or total gram value alone is not evidence of a count. Never assume the whole analyzed amount is one unit.
        - For liquids or pourable foods like milk, juice, soup, smoothies, dal, sauces, or yogurt, use ml when the volume is clearer than a count.
        - For spooned foods like peanut butter, honey, oil, chutney, or ghee, use tbsp or tsp.
        - For packaged foods/drinks, use can, packet, bar, scoop, or bowl only when that unit is visible or strongly implied.
        - grams_per_unit is grams for one unit. For countable units, use total grams / visible quantity. For ml, use grams per ml.
        - Return [] when the evidence does not support a reliable non-gram option.
        """

        let text = try await callAI(prompt: prompt, image: image)
        return try parseServingUnitOptions(from: text, servingSizeGrams: servingSizeGrams)
    }

    private struct ParsedInitialServingUnitOptions {
        var options: [ServingUnitOption]
        var requiresFallback: Bool
    }

    private static func parseInitialServingUnitOptions(
        from json: [String: Any],
        servingSizeGrams: Double?
    ) -> ParsedInitialServingUnitOptions {
        guard let rawValue = json["unit_options"] ?? json["serving_unit_options"] else {
            return ParsedInitialServingUnitOptions(options: [], requiresFallback: true)
        }
        guard let rawOptions = rawValue as? [Any] else {
            return ParsedInitialServingUnitOptions(options: [], requiresFallback: true)
        }
        guard !rawOptions.isEmpty else {
            return ParsedInitialServingUnitOptions(options: [], requiresFallback: false)
        }

        var seen = Set<String>()
        var options: [ServingUnitOption] = []
        for rawValue in rawOptions {
            guard let raw = rawValue as? [String: Any],
                  let unit = raw["unit"] as? String,
                  let quantity = strictJSONNumber(raw["quantity"]),
                  let gramsPerUnit = strictJSONNumber(raw["grams_per_unit"] ?? raw["gramsPerUnit"]),
                  quantity > 0,
                  gramsPerUnit > 0
            else {
                return ParsedInitialServingUnitOptions(options: [], requiresFallback: true)
            }

            let option = ServingUnitOption(
                unit: unit,
                gramsPerUnit: gramsPerUnit,
                quantity: quantity
            )
            guard option.isValid,
                  !option.isGramUnit,
                  servingUnitTotalIsConsistent(
                    quantity: quantity,
                    gramsPerUnit: gramsPerUnit,
                    servingSizeGrams: servingSizeGrams
                  )
            else {
                return ParsedInitialServingUnitOptions(options: [], requiresFallback: true)
            }

            if !seen.contains(option.id) {
                seen.insert(option.id)
                options.append(option)
            }
        }

        return ParsedInitialServingUnitOptions(
            options: Array(options.prefix(4)),
            requiresFallback: false
        )
    }

    private static func strictJSONNumber(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else { return nil }
        let result = number.doubleValue
        return result.isFinite ? result : nil
    }

    private static func servingUnitTotalIsConsistent(
        quantity: Double,
        gramsPerUnit: Double,
        servingSizeGrams: Double?
    ) -> Bool {
        guard let servingSizeGrams, servingSizeGrams > 0 else { return true }
        let representedGrams = quantity * gramsPerUnit
        let tolerance = max(5, servingSizeGrams * 0.15)
        return abs(representedGrams - servingSizeGrams) <= tolerance
    }

    private static func parseServingUnitOptions(from text: String, servingSizeGrams: Double?) throws -> [ServingUnitOption] {
        let jsonString = extractJSON(from: text)
        guard let data = jsonString.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw AnalysisError.invalidResponse }
        let parsed = parseInitialServingUnitOptions(
            from: json,
            servingSizeGrams: servingSizeGrams
        )
        return parsed.requiresFallback ? [] : parsed.options
    }
}
