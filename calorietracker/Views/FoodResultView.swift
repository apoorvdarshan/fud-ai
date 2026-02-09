import SwiftUI

struct FoodResultView: View {
    let image: UIImage?
    let emoji: String?
    let source: FoodSource

    // Base values (original nutrition from Gemini for the original serving size)
    let baseCalories: Int
    let baseProtein: Int
    let baseCarbs: Int
    let baseFat: Int
    let baseServingSizeGrams: Double
    let baseSugar: Double?
    let baseAddedSugar: Double?
    let baseFiber: Double?
    let baseSaturatedFat: Double?
    let baseMonounsaturatedFat: Double?
    let basePolyunsaturatedFat: Double?
    let baseCholesterol: Double?
    let baseSodium: Double?
    let basePotassium: Double?

    @State var name: String
    @State var servingSizeGrams: Double
    @State private var servingSizeText: String
    @State var mealType: MealType = .snack

    var onLog: (FoodEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    // Scaling factor based on user-adjusted serving size
    private var scale: Double {
        guard baseServingSizeGrams > 0 else { return 1 }
        return servingSizeGrams / baseServingSizeGrams
    }

    // Computed scaled nutrition values
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

    init(
        image: UIImage?,
        emoji: String? = nil,
        source: FoodSource,
        name: String,
        calories: Int,
        protein: Int,
        carbs: Int,
        fat: Int,
        servingSizeGrams: Double = 100,
        sugar: Double? = nil,
        addedSugar: Double? = nil,
        fiber: Double? = nil,
        saturatedFat: Double? = nil,
        monounsaturatedFat: Double? = nil,
        polyunsaturatedFat: Double? = nil,
        cholesterol: Double? = nil,
        sodium: Double? = nil,
        potassium: Double? = nil,
        onLog: @escaping (FoodEntry) -> Void
    ) {
        self.image = image
        self.emoji = emoji
        self.source = source
        self.baseCalories = calories
        self.baseProtein = protein
        self.baseCarbs = carbs
        self.baseFat = fat
        self.baseServingSizeGrams = servingSizeGrams
        self.baseSugar = sugar
        self.baseAddedSugar = addedSugar
        self.baseFiber = fiber
        self.baseSaturatedFat = saturatedFat
        self.baseMonounsaturatedFat = monounsaturatedFat
        self.basePolyunsaturatedFat = polyunsaturatedFat
        self.baseCholesterol = cholesterol
        self.baseSodium = sodium
        self.basePotassium = potassium
        self._name = State(initialValue: name)
        self._servingSizeGrams = State(initialValue: servingSizeGrams)
        self._servingSizeText = State(initialValue: Self.formatGrams(servingSizeGrams))
        self.onLog = onLog
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
                if let image {
                    Section {
                        HStack {
                            Spacer()
                            Image(uiImage: image)
                                .resizable()
                                .scaledToFit()
                                .frame(maxHeight: 200)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Spacer()
                        }
                        .listRowBackground(Color.clear)
                    }
                } else if let emoji {
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

                Section {
                    Button(action: logFood) {
                        Text("Log Food")
                            .font(.headline)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .listRowBackground(Color.clear)
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Review Food")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func logFood() {
        let entry = FoodEntry(
            name: name,
            calories: scaledCalories,
            protein: scaledProtein,
            carbs: scaledCarbs,
            fat: scaledFat,
            imageData: image?.jpegData(compressionQuality: 0.5),
            emoji: emoji,
            source: source,
            mealType: mealType,
            sugar: scaledSugar,
            addedSugar: scaledAddedSugar,
            fiber: scaledFiber,
            saturatedFat: scaledSaturatedFat,
            monounsaturatedFat: scaledMonounsaturatedFat,
            polyunsaturatedFat: scaledPolyunsaturatedFat,
            cholesterol: scaledCholesterol,
            sodium: scaledSodium,
            potassium: scaledPotassium
        )
        onLog(entry)
        dismiss()
    }
}

struct NutritionDisplayRow: View {
    let label: String
    let value: String
    let unit: String

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .fontWeight(.medium)
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(width: 36, alignment: .leading)
        }
    }
}

struct OptionalNutritionDisplayRow: View {
    let label: String
    let value: Double?
    let unit: String

    var body: some View {
        HStack {
            Text(label)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value.map { String(format: "%.1f", $0) } ?? "—")
                .fontWeight(.medium)
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(width: 36, alignment: .leading)
        }
    }
}
