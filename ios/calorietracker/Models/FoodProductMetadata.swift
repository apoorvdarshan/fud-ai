import Foundation

/// Product-label details supplied by Open Food Facts for a barcode lookup.
/// Nutrition remains on `FoodEntry`; this type keeps optional product metadata
/// separate so legacy and non-barcode food logs stay lightweight.
struct FoodProductMetadata: Codable, Equatable, Sendable {
    var barcode: String
    var packageQuantity: String?
    var ingredientsText: String?
    var allergens: [String]
    var traces: [String]
    var nutriScore: String?
    var novaGroup: Int?
    var ecoScore: String?
    var labels: [String]
    var categories: [String]
    var imageURL: URL?
    /// Where the product came from when it was not Open Food Facts (#533):
    /// `FoodDataCentralService.dataSourceID` for USDA FoodData Central. Stored as
    /// text so an unknown value from a newer app version never fails decoding.
    var dataSource: String? = nil

    /// Display name of `dataSource`, nil for Open Food Facts and unknown values.
    var dataSourceName: String? {
        switch dataSource {
        case FoodDataCentralService.dataSourceID?:
            String(localized: "USDA FoodData Central", comment: "Product Information source for foods found in the USDA FoodData Central database.")
        default:
            nil
        }
    }

    var hasDisplayDetails: Bool {
        !barcode.isEmpty
            || packageQuantity != nil
            || ingredientsText != nil
            || !allergens.isEmpty
            || !traces.isEmpty
            || nutriScore != nil
            || novaGroup != nil
            || ecoScore != nil
            || !labels.isEmpty
            || !categories.isEmpty
            || dataSourceName != nil
    }
}
