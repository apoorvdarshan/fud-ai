import SwiftUI

struct EditFoodEntryView: View {
    let entry: FoodEntry
    @Environment(FoodStore.self) private var foodStore
    @Environment(\.dismiss) private var dismiss

    // Base values (the entry's nutrition at its logged serving size)
    private let baseCalories: Int
    private let baseProtein: Int
    private let baseCarbs: Int
    private let baseFat: Int
    private let baseServingSizeGrams: Double
    private let baseSugar: Double?
    private let baseAddedSugar: Double?
    private let baseFiber: Double?
    private let baseSaturatedFat: Double?
    private let baseMonounsaturatedFat: Double?
    private let basePolyunsaturatedFat: Double?
    private let baseCholesterol: Double?
    private let baseSodium: Double?
    private let basePotassium: Double?

    @State private var name: String
    @State private var servingSizeGrams: Double
    @State private var servingSizeText: String
    @State private var mealType: MealType

    private var scale: Double {
        guard baseServingSizeGrams > 0 else { return 1 }
        return servingSizeGrams / baseServingSizeGrams
    }

    private var scaledCalories: Int { Int(round(Double(baseCalories) * scale)) }
    private var scaledProtein: Int { Int(round(Double(baseProtein) * scale)) }
    private var scaledCarbs: Int { Int(round(Double(baseCarbs) * scale)) }
    private var scaledFat: Int { Int(round(Double(baseFat) * scale)) }
    private var scaledSugar: Double? { baseSugar.map { round($0 * scale * 10) / 10 } }
    private var scaledAddedSugar: Double? { baseAddedSugar.map { round($0 * scale * 10) / 10 } }
    private var scaledFiber: Double? { baseFiber.map { round($0 * scale * 10) / 10 } }
    private var scaledSaturatedFat: Double? { baseSaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledMonounsaturatedFat: Double? { baseMonounsaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledPolyunsaturatedFat: Double? { basePolyunsaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledCholesterol: Double? { baseCholesterol.map { round($0 * scale * 10) / 10 } }
    private var scaledSodium: Double? { baseSodium.map { round($0 * scale * 10) / 10 } }
    private var scaledPotassium: Double? { basePotassium.map { round($0 * scale * 10) / 10 } }

    init(entry: FoodEntry) {
        self.entry = entry
        let serving = entry.servingSizeGrams ?? 100
        self.baseCalories = entry.calories
        self.baseProtein = entry.protein
        self.baseCarbs = entry.carbs
        self.baseFat = entry.fat
        self.baseServingSizeGrams = serving
        self.baseSugar = entry.sugar
        self.baseAddedSugar = entry.addedSugar
        self.baseFiber = entry.fiber
        self.baseSaturatedFat = entry.saturatedFat
        self.baseMonounsaturatedFat = entry.monounsaturatedFat
        self.basePolyunsaturatedFat = entry.polyunsaturatedFat
        self.baseCholesterol = entry.cholesterol
        self.baseSodium = entry.sodium
        self.basePotassium = entry.potassium
        self._name = State(initialValue: entry.name)
        self._servingSizeGrams = State(initialValue: serving)
        self._servingSizeText = State(initialValue: Self.formatGrams(serving))
        self._mealType = State(initialValue: entry.mealType)
    }

    private static func formatGrams(_ value: Double) -> String {
        if value == value.rounded() {
            return String(Int(value))
        }
        return String(format: "%.1f", value)
    }

    var body: some View {
        NavigationStack {
            List {
                if let imageData = entry.imageData, let uiImage = UIImage(data: imageData) {
                    Section {
                        HStack {
                            Spacer()
                            Image(uiImage: uiImage)
                                .resizable()
                                .scaledToFit()
                                .frame(maxHeight: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Spacer()
                        }
                        .listRowBackground(Color.clear)
                    }
                } else if let emoji = entry.emoji {
                    Section {
                        HStack {
                            Spacer()
                            Text(emoji)
                                .font(.system(size: 80))
                            Spacer()
                        }
                        .listRowBackground(Color.clear)
                    }
                }

                Section("Food Details") {
                    HStack {
                        Text("Name")
                        Spacer()
                        TextField("Food name", text: $name)
                            .multilineTextAlignment(.trailing)
                    }
                }

                Section("Serving") {
                    HStack {
                        Text("Quantity")
                        Spacer()
                        TextField("0", text: $servingSizeText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 80)
                            .onChange(of: servingSizeText) { _, newValue in
                                if let parsed = Double(newValue), parsed > 0 {
                                    servingSizeGrams = parsed
                                }
                            }
                        Text("g")
                            .foregroundStyle(.secondary)
                            .frame(width: 36, alignment: .leading)
                    }
                }

                Section("Nutrition") {
                    NutritionDisplayRow(label: "Calories", value: "\(scaledCalories)", unit: "kcal")
                    NutritionDisplayRow(label: "Protein", value: "\(scaledProtein)", unit: "g")
                    NutritionDisplayRow(label: "Carbs", value: "\(scaledCarbs)", unit: "g")
                    NutritionDisplayRow(label: "Fat", value: "\(scaledFat)", unit: "g")
                }

                Section {
                    DisclosureGroup("More Nutrition") {
                        OptionalNutritionDisplayRow(label: "Sugar", value: scaledSugar, unit: "g")
                        OptionalNutritionDisplayRow(label: "Added Sugar", value: scaledAddedSugar, unit: "g")
                        OptionalNutritionDisplayRow(label: "Fiber", value: scaledFiber, unit: "g")
                        OptionalNutritionDisplayRow(label: "Saturated Fat", value: scaledSaturatedFat, unit: "g")
                        OptionalNutritionDisplayRow(label: "Mono Fat", value: scaledMonounsaturatedFat, unit: "g")
                        OptionalNutritionDisplayRow(label: "Poly Fat", value: scaledPolyunsaturatedFat, unit: "g")
                        OptionalNutritionDisplayRow(label: "Cholesterol", value: scaledCholesterol, unit: "mg")
                        OptionalNutritionDisplayRow(label: "Sodium", value: scaledSodium, unit: "mg")
                        OptionalNutritionDisplayRow(label: "Potassium", value: scaledPotassium, unit: "mg")
                    }
                    .tint(AppColors.calorie)
                }

                Section("Meal") {
                    Picker("Meal Type", selection: $mealType) {
                        ForEach(MealType.allCases, id: \.self) { meal in
                            Label(meal.displayName, systemImage: meal.icon)
                                .tag(meal)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(AppColors.calorie)
                }

            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Edit Food")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: saveChanges)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .tint(AppColors.calorie)
                }
            }
        }
    }

    private func saveChanges() {
        let updated = FoodEntry(
            id: entry.id,
            name: name,
            calories: scaledCalories,
            protein: scaledProtein,
            carbs: scaledCarbs,
            fat: scaledFat,
            timestamp: entry.timestamp,
            imageData: entry.imageData,
            emoji: entry.emoji,
            source: entry.source,
            mealType: mealType,
            sugar: scaledSugar,
            addedSugar: scaledAddedSugar,
            fiber: scaledFiber,
            saturatedFat: scaledSaturatedFat,
            monounsaturatedFat: scaledMonounsaturatedFat,
            polyunsaturatedFat: scaledPolyunsaturatedFat,
            cholesterol: scaledCholesterol,
            sodium: scaledSodium,
            potassium: scaledPotassium,
            servingSizeGrams: servingSizeGrams
        )
        foodStore.updateEntry(updated)
        dismiss()
    }
}
