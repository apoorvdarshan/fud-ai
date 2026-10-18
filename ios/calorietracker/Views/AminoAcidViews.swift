import SwiftUI

/// Nutrition Details section for the opt-in amino acid estimates (#491).
struct AminoAcidDailySection: View {
    let totals: [AminoAcid: Double]
    let bodyWeightKg: Double

    var body: some View {
        Section {
            ForEach(AminoAcid.allCases) { aminoAcid in
                AminoAcidDetailRow(
                    aminoAcid: aminoAcid,
                    grams: totals[aminoAcid],
                    referenceGrams: aminoAcid.referenceGrams(bodyWeightKg: bodyWeightKg)
                )
            }
        } header: {
            Text("Amino acids")
        } footer: {
            Text("AI estimates from foods analyzed while Amino acids is on. Reference values use the WHO/FAO/UNU 2007 adult requirement for your body weight. They are a reference, not a target.")
        }
    }
}

private struct AminoAcidDetailRow: View {
    let aminoAcid: AminoAcid
    let grams: Double?
    let referenceGrams: Double?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "atom")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(aminoAcid.displayName)
                    .font(.system(.body, design: .rounded))
                if let referenceGrams {
                    Text("Reference \(AminoAcidEstimates.formattedGrams(referenceGrams)) g")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(grams.map(AminoAcidEstimates.formattedGrams) ?? "—")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .contentTransition(.numericText())
                Text("g")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Caption that introduces the amino acid rows inside a "More Nutrition" group.
struct AminoAcidEstimateHeader: View {
    var body: some View {
        Text("Amino acids (AI estimate)")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.secondary)
    }
}
