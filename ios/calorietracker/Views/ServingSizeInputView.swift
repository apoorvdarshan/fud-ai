import SwiftUI

struct ServingSizeInputView: View {
    let image: UIImage
    let labelAnalysis: GeminiService.NutritionLabelAnalysis

    @State private var servingAmount: String
    var onContinue: (GeminiService.FoodAnalysis) -> Void
    @Environment(\.dismiss) private var dismiss
    @AppStorage(EnergyUnit.storageKey) private var energyUnit = EnergyUnit.defaultUnit

    init(
        image: UIImage,
        labelAnalysis: GeminiService.NutritionLabelAnalysis,
        onContinue: @escaping (GeminiService.FoodAnalysis) -> Void
    ) {
        self.image = image
        self.labelAnalysis = labelAnalysis
        self.onContinue = onContinue
        let defaultAmount = labelAnalysis.servingSizeGrams.map { String(Int($0)) } ?? "100"
        self._servingAmount = State(initialValue: defaultAmount)
    }

    private var grams: Double {
        Double(servingAmount) ?? 100
    }

    private var preview: GeminiService.FoodAnalysis {
        labelAnalysis.scaled(to: grams)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Spacer()
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 150)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                        Spacer()
                    }
                    .listRowBackground(Color.clear)
                }

                Section("Product") {
                    LabeledContent("Name", value: labelAnalysis.name)
                }

                Section("How much are you eating?") {
                    HStack {
                        TextField("Amount", text: $servingAmount)
                            .keyboardType(.numberPad)
                            .font(.title2)
                            .fontWeight(.semibold)
                        Text("grams")
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Estimated Nutrition") {
                    LabeledContent("Calories", value: energyUnit.format(kcal: preview.calories))
                    LabeledContent("Protein", value: "\(MacroValueFormatter.string(preview.protein)) g")
                    LabeledContent("Carbs", value: "\(MacroValueFormatter.string(preview.carbs)) g")
                    LabeledContent("Fat", value: "\(MacroValueFormatter.string(preview.fat)) g")
                }

                Section {
                    Button(action: {
                        onContinue(preview)
                    }) {
                        Text("Continue")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .listRowBackground(Color.clear)
                }
            }
            .navigationTitle("Serving Size")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
