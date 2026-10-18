import SwiftUI

struct FoodProductMetadataSection: View {
    let metadata: FoodProductMetadata
    /// Ingredient label entries that mention one of these are highlighted (#428).
    var allergenSensitivities: [String] = []
    @AppStorage(ProductInfoSettings.isShownKey) private var isShown = true
    @AppStorage(ProductInfoSettings.hiddenFieldsKey) private var hiddenFieldsRaw = ""

    private var visibleFields: [ProductInfoField] {
        ProductInfoSettings.visibleFields(
            for: metadata,
            isShown: isShown,
            hiddenFields: ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw)
        )
    }

    var body: some View {
        let fields = visibleFields
        if !fields.isEmpty {
            Section("Product Information") {
                ForEach(fields) { field in
                    row(for: field)
                }
            }
        }
    }

    @ViewBuilder
    private func row(for field: ProductInfoField) -> some View {
        switch field {
        case .barcode:
            ProductMetadataRow(title: field.title, value: metadata.barcode)
        case .source:
            if let value = metadata.dataSourceName {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .package:
            if let value = metadata.packageQuantity {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .nutriScore:
            if let value = metadata.nutriScore {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .novaGroup:
            if let value = metadata.novaGroup {
                ProductMetadataRow(title: field.title, value: "\(value)")
            }
        case .ecoScore:
            if let value = metadata.ecoScore {
                ProductMetadataRow(title: field.title, value: value)
            }
        case .allergens:
            ProductMetadataRow(title: field.title, value: metadata.allergens.formatted())
        case .traces:
            ProductMetadataRow(title: field.title, value: metadata.traces.formatted())
        case .labels:
            ProductMetadataRow(title: field.title, value: metadata.labels.formatted())
        case .categories:
            ProductMetadataRow(title: field.title, value: metadata.categories.formatted())
        case .ingredientLabel:
            if let ingredients = metadata.ingredientsText {
                IngredientLabelView(
                    title: field.title,
                    text: ingredients,
                    sensitivities: allergenSensitivities
                )
            }
        }
    }
}

extension ProductInfoField {
    var title: LocalizedStringKey {
        switch self {
        case .barcode: "Barcode"
        case .source: "Source"
        case .package: "Package"
        case .nutriScore: "Nutri-Score"
        case .novaGroup: "NOVA Group"
        case .ecoScore: "Eco-Score"
        case .allergens: "Allergens"
        case .traces: "May Contain"
        case .labels: "Labels"
        case .categories: "Categories"
        case .ingredientLabel: "Ingredient Label"
        }
    }
}

/// The scanned ingredient label as a readable list (#428), display only.
/// Allergens Open Food Facts flags are bold; entries matching the user's
/// allergen sensitivities get a warning icon. Statements after the list
/// ("May contain …") stay sentences below it, and labels that do not split into
/// a list stay a paragraph with the same warning. VoiceOver announces entries
/// with either kind of allergen emphasis as possible allergens.
private struct IngredientLabelView: View {
    let title: LocalizedStringKey
    let text: String
    let sensitivities: [String]

    var body: some View {
        let label = IngredientLabelParser.parse(text, sensitivities: sensitivities)
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            if label.items.isEmpty {
                if let paragraph = label.paragraph {
                    row(for: paragraph, bulleted: false)
                }
            } else {
                ForEach(Array(label.items.enumerated()), id: \.offset) { _, item in
                    row(for: item)
                }
                ForEach(Array(label.notes.enumerated()), id: \.offset) { index, note in
                    row(for: note, bulleted: false)
                        .padding(.top, index == 0 ? 4 : 0)
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    /// Notes after the list are plain sentences without a bullet.
    private func row(for item: IngredientLabelItem, bulleted: Bool = true) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            if item.matchesSensitivity {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
            } else if bulleted {
                Text(verbatim: "•")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Text(Self.attributed(item.segments))
                .font(item.matchesSensitivity ? .subheadline.weight(.semibold) : .subheadline)
                .foregroundStyle(item.matchesSensitivity ? .primary : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            item.announcesAllergen ? Text("Possible allergen: \(item.text)") : Text(verbatim: item.text)
        )
    }

    private static func attributed(_ segments: [IngredientLabelItem.Segment]) -> AttributedString {
        var result = AttributedString()
        for segment in segments {
            var part = AttributedString(segment.text)
            if segment.isAllergen {
                part.inlinePresentationIntent = .stronglyEmphasized
                part.foregroundColor = .primary
            }
            result += part
        }
        return result
    }
}

private struct ProductMetadataRow: View {
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
            Spacer(minLength: 12)
            Text(value)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
        }
        .accessibilityElement(children: .combine)
    }
}
