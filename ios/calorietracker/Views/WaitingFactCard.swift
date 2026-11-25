import SwiftUI

/// A bundled fact, chosen once per wait so progress updates never replace text mid-read.
struct WaitingFactCard: View {
    @State private var fact = Fact.allCases.randomElement() ?? .beans

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label {
                Text("Did you know?", tableName: "WaitingFacts")
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
            } icon: {
                Image(systemName: "sparkles")
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityHidden(true)
            }
            Text(fact.text)
                .font(.system(.body, design: .serif))
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
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
