import SwiftUI

enum ManualEntryInput {
    static func optionalNutritionValue(_ text: String, locale: Locale = .current) -> Double? {
        guard let value = ServingUnitEditor.parseDecimal(text, locale: locale), value >= 0 else {
            return nil
        }
        return value
    }
}

struct ManualEntryView: View {
    @State private var name = ""
    @State private var calories = ""
    @State private var protein = ""
    @State private var carbs = ""
    @State private var fat = ""
    @State private var fiber = ""
    @State private var mealType: MealType = .currentMeal
    @State private var submissionGate = FoodSubmissionGate()
    @FocusState private var focused: Field?

    let logDate: Date
    var onCancel: () -> Void
    var onSave: (FoodEntry) -> Void

    private enum Field { case name, calories, protein, carbs, fat, fiber }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty &&
        Int(calories) != nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Text("Manual Entry")
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    field(label: "Name", text: $name, placeholder: "e.g. Homemade salad", keyboard: .default, focus: .name)

                    HStack(spacing: 10) {
                        numberField(label: "Calories", text: $calories, focus: .calories)
                        numberField(label: "Protein (g)", text: $protein, focus: .protein)
                    }

                    HStack(spacing: 10) {
                        numberField(label: "Carbs (g)", text: $carbs, focus: .carbs)
                        numberField(label: "Fat (g)", text: $fat, focus: .fat)
                    }

                    numberField(
                        label: "\(LocalizedDisplayText.text("Fiber", polish: "Błonnik")) (g)",
                        text: $fiber,
                        focus: .fiber
                    )

                    // Meal Type — same .menu picker style used by FoodResultView /
                    // EditFoodEntryView so manual logging assigns to a specific meal
                    // (defaults to whatever currentMeal returns for the time of day).
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Meal")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        HStack {
                            Text("Meal Type")
                            Spacer()
                            Picker("Meal Type", selection: $mealType) {
                                ForEach(MealType.allCases, id: \.self) { meal in
                                    Label(meal.displayName, systemImage: meal.icon).tag(meal)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(AppColors.calorie)
                            .labelsHidden()
                        }
                        .padding(10)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.quaternarySystemFill)))
                    }

                    Button {
                        guard submissionGate.begin() else { return }
                        let entry = FoodEntry(
                            name: name.trimmingCharacters(in: .whitespaces),
                            calories: Int(calories) ?? 0,
                            protein: ServingUnitEditor.parseDecimal(protein) ?? 0,
                            carbs: ServingUnitEditor.parseDecimal(carbs) ?? 0,
                            fat: ServingUnitEditor.parseDecimal(fat) ?? 0,
                            timestamp: logDate,
                            source: .manual,
                            mealType: mealType,
                            fiber: ManualEntryInput.optionalNutritionValue(fiber)
                        )
                        onSave(entry)
                    } label: {
                        Text("Save")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .controlSize(.large)
                    .disabled(!canSave || submissionGate.isSubmitting)

                    Button("Cancel") { onCancel() }
                        .foregroundStyle(.secondary)
                }
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(AppColors.appBackground)
            .toolbar {
                if focused != nil {
                    ToolbarItemGroup(placement: .keyboard) {
                        Spacer()
                        Button("Done") { dismissKeyboard() }
                    }
                }
            }
            .onAppear { focused = .name }
        }
        .presentationDetents([.large])
    }

    private func dismissKeyboard() {
        focused = nil
    }

    @ViewBuilder
    private func field(label: String, text: Binding<String>, placeholder: String, keyboard: UIKeyboardType, focus: Field) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedDisplayText.text(label))
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(placeholder, text: text)
                .keyboardType(keyboard)
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .focused($focused, equals: focus)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.quaternarySystemFill)))
        }
    }

    @ViewBuilder
    private func numberField(label: String, text: Binding<String>, focus: Field) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedDisplayText.text(label))
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField("0", text: text)
                .keyboardType(focus == .calories ? .numberPad : .decimalPad)
                .textFieldStyle(.plain)
                .focused($focused, equals: focus)
                .padding(10)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(.quaternarySystemFill)))
        }
    }
}
