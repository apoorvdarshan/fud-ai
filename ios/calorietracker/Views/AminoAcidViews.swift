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
            Text("Amino acids").flashSectionHeading()
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
        VStack(alignment: .leading, spacing: 0) {
            NutritionDetailRow(
                icon: "atom", label: aminoAcid.displayName,
                value: grams.map(AminoAcidEstimates.formattedGrams) ?? "—", unit: "g"
            )
            if let referenceGrams {
                Text(aminoAcid.referenceLabel(grams: referenceGrams))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.leading, 32)
                    .padding(.bottom, 6)
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
