import SwiftUI

struct OptionalNutrientGoalsSettingsView: View {
    let profile: UserProfile

    @AppStorage(OptionalNutrientGoals.storageKey) private var storedGoalsData = Data()
    @AppStorage(AminoAcidSettings.enabledKey) private var aminoAcidsEnabled = false
    @State private var goals: OptionalNutrientGoals = .current
    @State private var editingNutrient: OptionalNutrient?

    var body: some View {
        List {
            Section {
                ForEach(OptionalNutrient.allCases) { nutrient in
                    Button {
                        editingNutrient = nutrient
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: nutrient.iconName)
                                .foregroundStyle(AppColors.calorie)
                                .frame(width: 22)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(nutrient.displayName)
                                    .foregroundStyle(.primary)
                                Text(nutrient.localizedGoalStyle)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(goals.goal(for: nutrient)) \(nutrient.unit)")
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Other Nutrients")
            } footer: {
                Text("Separate from calorie, protein, carb, and fat goals.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Toggle(isOn: $aminoAcidsEnabled) {
                    HStack(spacing: 12) {
                        Image(systemName: "atom")
                            .foregroundStyle(AppColors.calorie)
                            .frame(width: 22)
                        Text("Amino acids")
                    }
                }
                .tint(AppColors.calorie)
            } footer: {
                Text("When on, AI food analyses also estimate the 9 essential amino acids. Values are AI estimates, not measurements.")
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Other Nutrients")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            goals = OptionalNutrientGoals.decoded(from: storedGoalsData)
        }
        .onChange(of: storedGoalsData) { _, newData in
            goals = OptionalNutrientGoals.decoded(from: newData)
        }
        .sheet(item: $editingNutrient) { nutrient in
            NutritionPickerSheet(
                label: nutrient.displayName,
                unit: nutrient.unit,
                currentValue: goals.goal(for: nutrient),
                range: nutrient.range,
                step: nutrient.step,
                allowsCustomValue: true,
                guidanceUpperLimit: nutrient.generalAdultUpperLimit,
                customValueDetail: nutrient.customValueDetail
            ) { value in
                save(goals.settingGoal(value, for: nutrient))
            }
        }
    }

    private func save(_ newGoals: OptionalNutrientGoals) {
        let normalized = newGoals.mergedWithDefaults()
        goals = normalized
        storedGoalsData = normalized.encodedData
    }
}
