import SwiftUI

struct FoodProductMetadataSection: View {
    let metadata: FoodProductMetadata
    var allergenAnalysis: AllergenAnalysis? = nil

    var body: some View {
        Section("Product Information") {
            ProductMetadataRow(title: "Barcode", value: metadata.barcode)
            if let value = metadata.packageQuantity {
                ProductMetadataRow(title: "Package", value: value)
            }
            if let value = metadata.nutriScore {
                ProductMetadataRow(title: "Nutri-Score", value: value)
            }
            if let value = metadata.novaGroup {
                ProductMetadataRow(title: "NOVA Group", value: "\(value)")
            }
            if let value = metadata.ecoScore {
                ProductMetadataRow(title: "Eco-Score", value: value)
            }
            if !metadata.allergens.isEmpty {
                ProductMetadataRow(title: "Allergens", value: metadata.allergens.formatted())
            }
            if !metadata.traces.isEmpty {
                ProductMetadataRow(title: "May Contain", value: metadata.traces.formatted())
            }
            if let allergenAnalysis {
                ProductMetadataRow(title: "Allergen Check", value: allergenAnalysis.summary)
            }
            if !metadata.labels.isEmpty {
                ProductMetadataRow(title: "Labels", value: metadata.labels.formatted())
            }
            if !metadata.categories.isEmpty {
                ProductMetadataRow(title: "Categories", value: metadata.categories.formatted())
            }
            if let ingredients = metadata.ingredientsText {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Ingredient Label")
                        .font(.subheadline.weight(.semibold))
                    Text(ingredients)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        }
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
