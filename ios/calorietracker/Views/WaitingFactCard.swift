import SwiftUI

/// A bundled fact, chosen once per wait so progress updates never replace text mid-read.
struct WaitingFactCard: View {
    @Environment(\.locale) private var locale
    @State private var card: WaitingFactFeed.Card?

    init(card: WaitingFactFeed.Card? = nil) { _card = State(initialValue: card) }

    var body: some View {
        let language = WaitingFactCatalog.language(for: locale)
        // Keep the very first frame readable, including when SwiftUI is presenting
        // the analysis sheet at the same time that the app is warming its catalog.
        cardView(card ?? .editorial(1))
            .onAppear {
                guard card == nil else { return }
                card = WaitingFactFeed.shared.nextReady(language: language)
            }
            .task { await WaitingFactFeed.shared.prepare() }
    }

    private func cardView(_ card: WaitingFactFeed.Card) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label {
                Text("Did you know?", tableName: "WaitingFacts")
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
            } icon: {
                Image(systemName: "sparkles")
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityHidden(true)
            }
            switch card {
            case .editorial(let index):
                Text(Fact.allCases[index].text)
                    .font(.system(.body, design: .serif))
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            case .measurement(let entry):
                VStack(alignment: .leading, spacing: 3) {
                    Text(verbatim: entry.food)
                        .font(.system(.body, design: .serif, weight: .semibold))
                    if entry.preparation != "plain" {
                        Text(preparation(entry.preparation))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(summary(entry))
                        .font(.system(.body, design: .serif))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Text(verbatim: "USDA FoodData Central")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .foregroundStyle(.primary)
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: 360, alignment: .leading)
        .background(AppColors.calorie.opacity(0.09), in: RoundedRectangle(cornerRadius: 18))
        .overlay {
            RoundedRectangle(cornerRadius: 18)
                .strokeBorder(AppColors.calorie.opacity(0.24), lineWidth: 1)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("analysis.waitingFact")
    }

    private func preparation(_ key: String) -> String {
        String(localized: String.LocalizationValue("prep_\(key)"), table: "NutritionFactLabels", locale: locale)
    }

    private func summary(_ entry: WaitingFactCatalog.Measurement) -> String {
        let nutrient = String(localized: entry.nutrient.title, locale: locale)
        let amount = Measurement(value: entry.amount, unit: entry.nutrient.unit).formatted(
            .measurement(width: .abbreviated, usage: .asProvided, numberFormatStyle: .number.precision(.fractionLength(0...2)))
                .locale(locale)
        )
        let pattern = String(localized: "measure_per_100g", table: "NutritionFactLabels", locale: locale)
        return String(format: pattern, locale: locale, nutrient, amount)
    }

    private enum Fact: CaseIterable {
        case vitaminC, beans, oats, vegetables, strength

        var text: LocalizedStringResource {
            switch self {
            case .vitaminC: .init("Vitamin C helps your body absorb iron from plant foods.", table: "WaitingFacts")
            case .beans: .init("Beans and lentils contain both protein and fiber.", table: "WaitingFacts")
            case .oats: .init("Oats contain beta-glucan, a type of soluble fiber.", table: "WaitingFacts")
            case .vegetables: .init("Cucumbers and tomatoes are more than 90% water by weight.", table: "WaitingFacts")
            case .strength: .init("Strength training works against resistance. Your own body weight counts, too.", table: "WaitingFacts")
            }
        }
    }
}
