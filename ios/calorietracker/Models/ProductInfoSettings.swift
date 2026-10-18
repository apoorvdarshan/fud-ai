import Foundation

/// Open Food Facts fields the barcode Product Information block can show (#427),
/// plus the data source when a product came from USDA FoodData Central (#533).
/// Raw values are persisted in `ProductInfoSettings.hiddenFieldsKey`, so keep them stable.
/// Allergen Check is deliberately not a field: it follows the user's allergen
/// sensitivities in its own section, so this setting can never hide it.
enum ProductInfoField: String, CaseIterable, Identifiable, Sendable {
    case barcode
    case source
    case package
    case nutriScore
    case novaGroup
    case ecoScore
    case allergens
    case traces
    case labels
    case categories
    case ingredientLabel

    var id: String { rawValue }

    func hasValue(in metadata: FoodProductMetadata) -> Bool {
        switch self {
        case .barcode: !metadata.barcode.isEmpty
        case .source: metadata.dataSourceName != nil
        case .package: metadata.packageQuantity != nil
        case .nutriScore: metadata.nutriScore != nil
        case .novaGroup: metadata.novaGroup != nil
        case .ecoScore: metadata.ecoScore != nil
        case .allergens: !metadata.allergens.isEmpty
        case .traces: !metadata.traces.isEmpty
        case .labels: !metadata.labels.isEmpty
        case .categories: !metadata.categories.isEmpty
        case .ingredientLabel: metadata.ingredientsText != nil
        }
    }
}

/// Settings > App Preferences > Product Information (#427). Defaults show every field.
enum ProductInfoSettings {
    static let isShownKey = "productInfoShown"
    static let hiddenFieldsKey = "productInfoHiddenFields"

    /// Decodes the comma-separated raw values; unknown values are ignored.
    static func hiddenFields(from storageValue: String) -> Set<ProductInfoField> {
        Set(
            storageValue
                .split(separator: ",")
                .compactMap { ProductInfoField(rawValue: $0.trimmingCharacters(in: .whitespaces)) }
        )
    }

    static func storageValue(for hiddenFields: Set<ProductInfoField>) -> String {
        ProductInfoField.allCases
            .filter(hiddenFields.contains)
            .map(\.rawValue)
            .joined(separator: ",")
    }

    /// Fields to render, in display order. Empty when the block is turned off or
    /// when every field with data is hidden, so callers can skip the section.
    static func visibleFields(
        for metadata: FoodProductMetadata,
        isShown: Bool,
        hiddenFields: Set<ProductInfoField>
    ) -> [ProductInfoField] {
        guard isShown else { return [] }
        return ProductInfoField.allCases.filter { field in
            !hiddenFields.contains(field) && field.hasValue(in: metadata)
        }
    }
}
