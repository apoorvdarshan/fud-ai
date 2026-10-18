import Foundation

/// The barcode lookup order: Open Food Facts first, then the nutrition label the
/// user saved for that barcode (#485), then USDA FoodData Central when the user
/// added their own key (#533). Later sources are tried only when Open Food Facts
/// has no product or no nutrition for the code.
enum BarcodeProductLookup {
    enum Outcome {
        case product(OpenFoodFactsService.LookupResult)
        case savedLabel(FoodEntry)

        var analysis: GeminiService.FoodAnalysis {
            switch self {
            case .product(let result): result.analysis
            case .savedLabel(let entry): entry.reviewAnalysis
            }
        }
    }

    /// Throws the Open Food Facts error when no later source has the product,
    /// except that a key FoodData Central refuses is reported so it can be fixed.
    /// Every barcode flow passes the diary, so saved labels always come before USDA.
    static func lookup(
        barcode: String,
        savedLabelEntries: [FoodEntry],
        usdaAPIKey: String?,
        includesProductImage: Bool = true,
        session: URLSession = .shared
    ) async throws -> Outcome {
        let openFoodFactsError: Error
        do {
            if includesProductImage {
                return .product(try await OpenFoodFactsService.lookupWithImage(barcode: barcode, session: session))
            }
            let analysis = try await OpenFoodFactsService.lookup(barcode: barcode, session: session)
            return .product(OpenFoodFactsService.LookupResult(analysis: analysis, productImageData: nil))
        } catch {
            guard BarcodeLabelMemory.allowsFallback(for: error) else { throw error }
            openFoodFactsError = error
        }

        if let savedLabel = BarcodeLabelMemory.latestLabelEntry(for: barcode, in: savedLabelEntries) {
            return .savedLabel(savedLabel)
        }

        guard let apiKey = FoodDataCentralSettings.usableKey(usdaAPIKey) else { throw openFoodFactsError }
        let usdaAnalysis: GeminiService.FoodAnalysis?
        do {
            usdaAnalysis = try await FoodDataCentralService.lookup(barcode: barcode, apiKey: apiKey, session: session)
        } catch FoodDataCentralService.LookupError.unavailable {
            usdaAnalysis = nil
        }
        guard let usdaAnalysis else { throw openFoodFactsError }
        return .product(OpenFoodFactsService.LookupResult(analysis: usdaAnalysis, productImageData: nil))
    }
}
