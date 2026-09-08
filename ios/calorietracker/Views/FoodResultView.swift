import SwiftUI

struct FoodSubmissionGate {
    private(set) var isSubmitting = false

    mutating func begin() -> Bool {
        guard !isSubmitting else { return false }
        isSubmitting = true
        return true
    }
}

struct FoodResultView: View {
    private enum ScrollTarget: Hashable {
        case quantity
    }

    let images: [UIImage]
    let emoji: String?
    let source: FoodSource
    let progressiveMeal: Bool
    let productMetadata: FoodProductMetadata?

    @State private var baseServingSizeGrams: Double
    @State private var servingUnitOptions: [ServingUnitOption]
    @State private var servingSizeIsKnown: Bool

    @State private var name: String
    @State private var servingSizeGrams: Double
    @State private var servingSizeText: String
    @State private var selectedServingUnitID: String
    @State private var quantityFocusRequest = 0
    @State private var isQuantityEditing = false
    @State private var nutritionUnlocked = false
    @State private var editableCalories: Int
    @State private var editableProtein: Double
    @State private var editableCarbs: Double
    @State private var editableFat: Double
    @State private var editableSugar: Double?
    @State private var editableAddedSugar: Double?
    @State private var editableFiber: Double?
    @State private var editableSaturatedFat: Double?
    @State private var editableMonounsaturatedFat: Double?
    @State private var editablePolyunsaturatedFat: Double?
    @State private var editableCholesterol: Double?
    @State private var editableCaffeine: Double?
    @State private var editableSupplementalNutrients: [String: Double]
    @State private var editableSodium: Double?
    @State private var editablePotassium: Double?
    @State private var editableTransFat: Double?
    @State private var editableCalcium: Double?
    @State private var editableIron: Double?
    @State private var editableMagnesium: Double?
    @State private var editableZinc: Double?
    @State private var editableVitaminA: Double?
    @State private var editableVitaminC: Double?
    @State private var editableVitaminD: Double?
    @State private var editableVitaminB12: Double?
    @State private var editableVitaminE: Double?
    @State private var editableVitaminK: Double?
    @State private var editableFolate: Double?
    @State private var editableOmega3: Double?
    @State private var editableIngredients: [MealIngredient]
    @State private var ingredientEditor: IngredientEditorTarget?
    @State private var showWhatIfSheet = false
    @State private var submissionGate = FoodSubmissionGate()
    @State var mealType: MealType = .currentMeal

    let logDate: Date
    let profile: UserProfile
    let dayEntries: [FoodEntry]
    let weightMetric: Bool
    var onLog: (FoodEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    // Scaling factor based on user-adjusted serving size
    private var scale: Double {
        guard baseServingSizeGrams > 0 else { return 1 }
        return servingSizeGrams / baseServingSizeGrams
    }

    // Computed scaled nutrition values
    private var scaledCalories: Int { Int(round(Double(editableCalories) * scale)) }
    private var scaledProtein: Double { editableProtein * scale }
    private var scaledCarbs: Double { editableCarbs * scale }
    private var scaledFat: Double { editableFat * scale }
    private var scaledSugar: Double? { editableSugar.map { round($0 * scale * 10) / 10 } }
    private var scaledAddedSugar: Double? { editableAddedSugar.map { round($0 * scale * 10) / 10 } }
    private var scaledFiber: Double? { editableFiber.map { round($0 * scale * 10) / 10 } }
    private var scaledSaturatedFat: Double? { editableSaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledMonounsaturatedFat: Double? { editableMonounsaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledPolyunsaturatedFat: Double? { editablePolyunsaturatedFat.map { round($0 * scale * 10) / 10 } }
    private var scaledCholesterol: Double? { editableCholesterol.map { round($0 * scale * 10) / 10 } }
    private var scaledCaffeine: Double? { editableCaffeine.map { round($0 * scale * 10) / 10 } }
    private var scaledSupplementalNutrients: [String: Double] {
        editableSupplementalNutrients.mapValues { round($0 * scale * 10) / 10 }
    }
    private var scaledSodium: Double? { editableSodium.map { round($0 * scale * 10) / 10 } }
    private var scaledPotassium: Double? { editablePotassium.map { round($0 * scale * 10) / 10 } }
    private var scaledTransFat: Double? { editableTransFat.map { round($0 * scale * 10) / 10 } }
    private var scaledCalcium: Double? { editableCalcium.map { round($0 * scale * 10) / 10 } }
    private var scaledIron: Double? { editableIron.map { round($0 * scale * 10) / 10 } }
    private var scaledMagnesium: Double? { editableMagnesium.map { round($0 * scale * 10) / 10 } }
    private var scaledZinc: Double? { editableZinc.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminA: Double? { editableVitaminA.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminC: Double? { editableVitaminC.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminD: Double? { editableVitaminD.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminB12: Double? { editableVitaminB12.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminE: Double? { editableVitaminE.map { round($0 * scale * 10) / 10 } }
    private var scaledVitaminK: Double? { editableVitaminK.map { round($0 * scale * 10) / 10 } }
    private var scaledFolate: Double? { editableFolate.map { round($0 * scale * 10) / 10 } }
    private var scaledOmega3: Double? { editableOmega3.map { round($0 * scale * 10) / 10 } }
    private var selectedServingOption: ServingUnitOption {
        ServingUnitOption.option(matching: selectedServingUnitID, in: servingUnitOptions)
    }
    private var selectedServingQuantity: Double? {
        ServingAmountExpression.evaluate(servingSizeText)
    }

    init(
        images: [UIImage] = [],
        emoji: String? = nil,
        source: FoodSource,
        name: String,
        calories: Int,
        protein: Double,
        carbs: Double,
        fat: Double,
        ingredients: [MealIngredient] = [],
        progressiveMeal: Bool = false,
        productMetadata: FoodProductMetadata? = nil,
        servingSizeGrams: Double = 100,
        sugar: Double? = nil,
        addedSugar: Double? = nil,
        fiber: Double? = nil,
        saturatedFat: Double? = nil,
        monounsaturatedFat: Double? = nil,
        polyunsaturatedFat: Double? = nil,
        cholesterol: Double? = nil,
        caffeine: Double? = nil,
        supplementalNutrients: [String: Double] = [:],
        sodium: Double? = nil,
        potassium: Double? = nil,
        transFat: Double? = nil,
        calcium: Double? = nil,
        iron: Double? = nil,
        magnesium: Double? = nil,
        zinc: Double? = nil,
        vitaminA: Double? = nil,
        vitaminC: Double? = nil,
        vitaminD: Double? = nil,
        vitaminB12: Double? = nil,
        vitaminE: Double? = nil,
        vitaminK: Double? = nil,
        folate: Double? = nil,
        omega3: Double? = nil,
        servingUnitOptions: [ServingUnitOption] = [],
        selectedServingUnit: String? = nil,
        selectedServingQuantity: Double? = nil,
        servingSizeIsKnown: Bool = true,
        logDate: Date = .now,
        profile: UserProfile,
        dayEntries: [FoodEntry],
        weightMetric: Bool,
        onLog: @escaping (FoodEntry) -> Void
    ) {
        let normalizedServingUnitOptions = servingSizeIsKnown
            ? ServingUnitOption.normalizedOptions(servingUnitOptions, totalGrams: servingSizeGrams)
            : [.loggedServing(quantity: servingSizeGrams)]
        let preferredServingUnit = servingSizeIsKnown
            ? (FoodMeasurementSettings.preferGramsByDefault ? nil : selectedServingUnit)
            : "serving"
        let initialServingUnitID = ServingUnitOption.initialUnitID(
            preferredUnit: preferredServingUnit,
            options: normalizedServingUnitOptions,
            defaultToGrams: servingSizeIsKnown && FoodMeasurementSettings.preferGramsByDefault
        )
        self.images = images
        self.emoji = emoji
        self.source = source
        self.progressiveMeal = progressiveMeal
        self.productMetadata = productMetadata
        self._baseServingSizeGrams = State(initialValue: servingSizeGrams)
        self._servingUnitOptions = State(initialValue: normalizedServingUnitOptions)
        self._servingSizeIsKnown = State(initialValue: servingSizeIsKnown)
        self._name = State(initialValue: name)
        self._servingSizeGrams = State(initialValue: servingSizeGrams)
        self._servingSizeText = State(initialValue: ServingUnitOption.initialQuantityText(
            totalGrams: servingSizeGrams,
            selectedUnitID: initialServingUnitID,
            selectedQuantity: selectedServingQuantity,
            options: normalizedServingUnitOptions
        ))
        self._selectedServingUnitID = State(initialValue: initialServingUnitID)
        self._editableCalories = State(initialValue: calories)
        self._editableProtein = State(initialValue: protein)
        self._editableCarbs = State(initialValue: carbs)
        self._editableFat = State(initialValue: fat)
        self._editableSugar = State(initialValue: sugar)
        self._editableAddedSugar = State(initialValue: addedSugar)
        self._editableFiber = State(initialValue: fiber)
        self._editableSaturatedFat = State(initialValue: saturatedFat)
        self._editableMonounsaturatedFat = State(initialValue: monounsaturatedFat)
        self._editablePolyunsaturatedFat = State(initialValue: polyunsaturatedFat)
        self._editableCholesterol = State(initialValue: cholesterol)
        self._editableCaffeine = State(initialValue: caffeine)
        self._editableSupplementalNutrients = State(initialValue: supplementalNutrients)
        self._editableSodium = State(initialValue: sodium)
        self._editablePotassium = State(initialValue: potassium)
        self._editableTransFat = State(initialValue: transFat)
        self._editableCalcium = State(initialValue: calcium)
        self._editableIron = State(initialValue: iron)
        self._editableMagnesium = State(initialValue: magnesium)
        self._editableZinc = State(initialValue: zinc)
        self._editableVitaminA = State(initialValue: vitaminA)
        self._editableVitaminC = State(initialValue: vitaminC)
        self._editableVitaminD = State(initialValue: vitaminD)
        self._editableVitaminB12 = State(initialValue: vitaminB12)
        self._editableVitaminE = State(initialValue: vitaminE)
        self._editableVitaminK = State(initialValue: vitaminK)
        self._editableFolate = State(initialValue: folate)
        self._editableOmega3 = State(initialValue: omega3)
        self._editableIngredients = State(initialValue: ingredients)
        self.logDate = logDate
        self.profile = profile
        self.dayEntries = dayEntries
        self.weightMetric = weightMetric
        self.onLog = onLog
    }

    private static func formatGrams(_ value: Double) -> String {
        if value == value.rounded() {
            return String(Int(value))
        }
        return String(format: "%.1f", value)
    }

    private var safeInverseScale: Double {
        scale > 0 ? scale : 1
    }

    private func decimalValue(from text: String) -> Double? {
        let normalized = text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".")
        guard !normalized.isEmpty, let value = Double(normalized) else {
            return nil
        }
        return max(0, value)
    }

    private func editText(_ value: Double?) -> String {
        value.map { String(format: "%.1f", $0) } ?? ""
    }

    private func displayText(_ value: Double?) -> String {
        value.map { String(format: "%.1f", $0) } ?? "—"
    }

    private func updateBaseCalories(from text: String) {
        editableCalories = Int(round((decimalValue(from: text) ?? 0) / safeInverseScale))
    }

    private func updateBaseDouble(from text: String, set: (Double) -> Void) {
        set((decimalValue(from: text) ?? 0) / safeInverseScale)
    }

    private func updateOptionalBaseDouble(from text: String, set: (Double?) -> Void) {
        set(decimalValue(from: text).map { $0 / safeInverseScale })
    }

    private func toggleNutritionLock() {
        nutritionUnlocked.toggle()
        if !nutritionUnlocked {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
    }

    private var scaledIngredients: [MealIngredient] {
        editableIngredients.map { $0.scaled(by: scale) }
    }

    private func applyIngredientChanges(_ displayedIngredients: [MealIngredient]) {
        editableIngredients = displayedIngredients
        let totals = displayedIngredients.ingredientTotals
        editableCalories = totals.calories
        editableProtein = totals.protein
        editableCarbs = totals.carbs
        editableFat = totals.fat
        guard totals.grams > 0 else { return }
        baseServingSizeGrams = totals.grams
        servingSizeGrams = totals.grams
        servingSizeText = Self.formatGrams(totals.grams)
        selectedServingUnitID = ServingUnitOption.grams.unit
        servingUnitOptions = []
        servingSizeIsKnown = true
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { scrollProxy in
                List {
                    if !images.isEmpty {
                        Section {
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 12) {
                                    ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                                        Image(uiImage: image)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(width: 220, height: 200)
                                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                            .overlay(alignment: .bottomTrailing) {
                                                if images.count > 1 {
                                                    Text("\(index + 1)/\(images.count)")
                                                        .font(.caption2.weight(.semibold))
                                                        .padding(.horizontal, 8)
                                                        .padding(.vertical, 5)
                                                        .background(.ultraThinMaterial, in: Capsule())
                                                        .padding(8)
                                                }
                                            }
                                    }
                                }
                                .scrollTargetLayout()
                            }
                            .scrollTargetBehavior(.viewAligned)
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

                    if let productMetadata, productMetadata.hasDisplayDetails {
                        FoodProductMetadataSection(metadata: productMetadata)
                    }

                    Section("Serving") {
                        HStack {
                            Text("Quantity")
                            Spacer()
                            ServingUnitEditor(
                                quantityText: $servingSizeText,
                                servingSizeGrams: $servingSizeGrams,
                                selectedUnitID: $selectedServingUnitID,
                                unitOptions: servingUnitOptions,
                                allowsGramUnit: servingSizeIsKnown,
                                focusRequest: quantityFocusRequest,
                                onEditingChanged: { editing in
                                    isQuantityEditing = editing
                                },
                                onClear: {
                                    servingSizeText = ""
                                    quantityFocusRequest += 1
                                }
                            )
                        }
                        .id(ScrollTarget.quantity)
                        if servingSizeIsKnown && !selectedServingOption.isGramUnit {
                            HStack {
                                Text("Total")
                                Spacer()
                                Text("~\(Self.formatGrams(servingSizeGrams)) g")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }

                    Section {
                        ReviewNutritionValueRow(
                            label: "Calories",
                            displayValue: "\(scaledCalories)",
                            editValue: "\(scaledCalories)",
                            unit: "kcal",
                            isUnlocked: nutritionUnlocked,
                            onEdit: updateBaseCalories
                        )
                        ReviewNutritionValueRow(
                            label: "Protein",
                            displayValue: MacroValueFormatter.string(scaledProtein),
                            editValue: MacroValueFormatter.string(scaledProtein),
                            unit: "g",
                            isUnlocked: nutritionUnlocked,
                            onEdit: { updateBaseDouble(from: $0) { editableProtein = $0 } }
                        )
                        ReviewNutritionValueRow(
                            label: "Carbs",
                            displayValue: MacroValueFormatter.string(scaledCarbs),
                            editValue: MacroValueFormatter.string(scaledCarbs),
                            unit: "g",
                            isUnlocked: nutritionUnlocked,
                            onEdit: { updateBaseDouble(from: $0) { editableCarbs = $0 } }
                        )
                        ReviewNutritionValueRow(
                            label: "Fat",
                            displayValue: MacroValueFormatter.string(scaledFat),
                            editValue: MacroValueFormatter.string(scaledFat),
                            unit: "g",
                            isUnlocked: nutritionUnlocked,
                            onEdit: { updateBaseDouble(from: $0) { editableFat = $0 } }
                        )
                    } header: {
                        HStack {
                            Text("Nutrition")
                            Spacer()
                            Button(action: toggleNutritionLock) {
                                Image(systemName: nutritionUnlocked ? "lock.open.fill" : "lock.fill")
                                    .font(.system(size: 14, weight: .semibold))
                            }
                            .buttonStyle(.borderless)
                            .foregroundStyle(nutritionUnlocked ? AppColors.calorie : .secondary)
                            .accessibilityLabel(nutritionUnlocked ? "Lock nutrition editing" : "Unlock nutrition editing")
                        }
                    }

                    MealIngredientsSection(
                        ingredients: scaledIngredients,
                        onEdit: { index in
                            ingredientEditor = IngredientEditorTarget(index: index, ingredient: scaledIngredients[index])
                        },
                        addMenu: AnyView(
                            IngredientAddMenuButton(
                                onManual: {
                                    ingredientEditor = IngredientEditorTarget(
                                        index: nil,
                                        ingredient: MealIngredient(name: "", grams: 100, calories: 0, protein: 0, carbs: 0, fat: 0)
                                    )
                                },
                                onIngredient: { ingredient in
                                    applyIngredientChanges(scaledIngredients + [ingredient])
                                }
                            )
                        )
                    )

                    Section {
                        DisclosureGroup("More Nutrition") {
                            ReviewNutritionValueRow(label: "Sugar", displayValue: displayText(scaledSugar), editValue: editText(scaledSugar), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableSugar = $0 } })
                            ReviewNutritionValueRow(label: "Added Sugar", displayValue: displayText(scaledAddedSugar), editValue: editText(scaledAddedSugar), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableAddedSugar = $0 } })
                            ReviewNutritionValueRow(label: "Fiber", displayValue: displayText(scaledFiber), editValue: editText(scaledFiber), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableFiber = $0 } })
                            ReviewNutritionValueRow(label: "Saturated Fat", displayValue: displayText(scaledSaturatedFat), editValue: editText(scaledSaturatedFat), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableSaturatedFat = $0 } })
                            ReviewNutritionValueRow(label: "Mono Fat", displayValue: displayText(scaledMonounsaturatedFat), editValue: editText(scaledMonounsaturatedFat), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableMonounsaturatedFat = $0 } })
                            ReviewNutritionValueRow(label: "Poly Fat", displayValue: displayText(scaledPolyunsaturatedFat), editValue: editText(scaledPolyunsaturatedFat), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editablePolyunsaturatedFat = $0 } })
                            ReviewNutritionValueRow(label: "Cholesterol", displayValue: displayText(scaledCholesterol), editValue: editText(scaledCholesterol), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableCholesterol = $0 } })
                            ReviewNutritionValueRow(label: "Caffeine", displayValue: displayText(scaledCaffeine), editValue: editText(scaledCaffeine), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableCaffeine = $0 } })
                            ReviewNutritionValueRow(label: "Sodium", displayValue: displayText(scaledSodium), editValue: editText(scaledSodium), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableSodium = $0 } })
                            ReviewNutritionValueRow(label: "Potassium", displayValue: displayText(scaledPotassium), editValue: editText(scaledPotassium), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editablePotassium = $0 } })
                            ReviewNutritionValueRow(label: "Trans Fat", displayValue: displayText(scaledTransFat), editValue: editText(scaledTransFat), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableTransFat = $0 } })
                            ReviewNutritionValueRow(label: "Calcium", displayValue: displayText(scaledCalcium), editValue: editText(scaledCalcium), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableCalcium = $0 } })
                            ReviewNutritionValueRow(label: "Iron", displayValue: displayText(scaledIron), editValue: editText(scaledIron), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableIron = $0 } })
                            ReviewNutritionValueRow(label: "Magnesium", displayValue: displayText(scaledMagnesium), editValue: editText(scaledMagnesium), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableMagnesium = $0 } })
                            ReviewNutritionValueRow(label: "Zinc", displayValue: displayText(scaledZinc), editValue: editText(scaledZinc), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableZinc = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin A", displayValue: displayText(scaledVitaminA), editValue: editText(scaledVitaminA), unit: "mcg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminA = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin C", displayValue: displayText(scaledVitaminC), editValue: editText(scaledVitaminC), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminC = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin D", displayValue: displayText(scaledVitaminD), editValue: editText(scaledVitaminD), unit: "mcg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminD = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin B12", displayValue: displayText(scaledVitaminB12), editValue: editText(scaledVitaminB12), unit: "mcg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminB12 = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin E", displayValue: displayText(scaledVitaminE), editValue: editText(scaledVitaminE), unit: "mg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminE = $0 } })
                            ReviewNutritionValueRow(label: "Vitamin K", displayValue: displayText(scaledVitaminK), editValue: editText(scaledVitaminK), unit: "mcg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableVitaminK = $0 } })
                            ReviewNutritionValueRow(label: "Folate", displayValue: displayText(scaledFolate), editValue: editText(scaledFolate), unit: "mcg", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableFolate = $0 } })
                            ReviewNutritionValueRow(label: "Omega-3", displayValue: displayText(scaledOmega3), editValue: editText(scaledOmega3), unit: "g", isUnlocked: nutritionUnlocked, dim: true, onEdit: { updateOptionalBaseDouble(from: $0) { editableOmega3 = $0 } })
                            ForEach(SupplementalNutrient.allCases) { nutrient in
                                let value = scaledSupplementalNutrients[nutrient.rawValue]
                                ReviewNutritionValueRow(
                                    label: nutrient.displayName,
                                    displayValue: displayText(value),
                                    editValue: editText(value),
                                    unit: "g",
                                    isUnlocked: nutritionUnlocked,
                                    dim: true,
                                    onEdit: { text in
                                        updateOptionalBaseDouble(from: text) { value in
                                            if let value {
                                                editableSupplementalNutrients[nutrient.rawValue] = value
                                            } else {
                                                editableSupplementalNutrients.removeValue(forKey: nutrient.rawValue)
                                            }
                                        }
                                    }
                                )
                            }
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
                .background(KeyboardDismissTapInstaller())
                .safeAreaInset(edge: .bottom) {
                    if isQuantityEditing {
                        Color.clear.frame(height: 12)
                    }
                }
                .onChange(of: isQuantityEditing) { _, editing in
                    guard editing else { return }
                    scrollQuantityIntoView(scrollProxy)
                }
                .navigationTitle("Review Food")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItemGroup(placement: .confirmationAction) {
                        Button("What if?") { showWhatIfSheet = true }
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .tint(AppColors.protein)

                        Button("Log", action: logFood)
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .tint(AppColors.calorie)
                            .disabled(submissionGate.isSubmitting)
                    }
                }
                .sheet(isPresented: $showWhatIfSheet) {
                    WhatIfMealImpactSheet(
                        entry: makeFoodEntry(includeImage: false),
                        dayEntries: dayEntries,
                        profile: profile,
                        weightMetric: weightMetric
                    )
                }
                .sheet(item: $ingredientEditor) { target in
                    IngredientEditorSheet(
                        target: target,
                        onSave: { ingredient in
                            var next = scaledIngredients
                            if let index = target.index, next.indices.contains(index) {
                                next[index] = ingredient
                            } else {
                                next.append(ingredient)
                            }
                            applyIngredientChanges(next)
                        },
                        onDelete: target.index.map { index in
                            {
                                var next = scaledIngredients
                                guard next.indices.contains(index) else { return }
                                next.remove(at: index)
                                applyIngredientChanges(next)
                            }
                        }
                    )
                }
            }
        }
    }

    private func scrollQuantityIntoView(_ proxy: ScrollViewProxy) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) {
            withAnimation(.easeInOut(duration: 0.2)) {
                proxy.scrollTo(ScrollTarget.quantity, anchor: .bottom)
            }
        }
    }

    private func logFood() {
        guard submissionGate.begin() else { return }
        let entry = makeFoodEntry(includeImage: true)
        onLog(entry)
        dismiss()
    }

    private func makeFoodEntry(includeImage: Bool) -> FoodEntry {
        FoodEntry(
            name: name,
            calories: scaledCalories,
            protein: scaledProtein,
            carbs: scaledCarbs,
            fat: scaledFat,
            timestamp: logDate,
            imageData: includeImage ? images.first?.jpegData(compressionQuality: 0.5) : nil,
            additionalImageData: includeImage ? images.dropFirst().compactMap { $0.jpegData(compressionQuality: 0.5) } : [],
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
            caffeine: scaledCaffeine,
            supplementalNutrients: scaledSupplementalNutrients,
            sodium: scaledSodium,
            potassium: scaledPotassium,
            transFat: scaledTransFat,
            calcium: scaledCalcium,
            iron: scaledIron,
            magnesium: scaledMagnesium,
            zinc: scaledZinc,
            vitaminA: scaledVitaminA,
            vitaminC: scaledVitaminC,
            vitaminD: scaledVitaminD,
            vitaminB12: scaledVitaminB12,
            vitaminE: scaledVitaminE,
            vitaminK: scaledVitaminK,
            folate: scaledFolate,
            omega3: scaledOmega3,
            servingSizeGrams: servingSizeIsKnown ? servingSizeGrams : nil,
            servingUnitOptions: servingSizeIsKnown ? servingUnitOptions : [],
            selectedServingUnit: servingSizeIsKnown
                ? (servingUnitOptions.isEmpty ? nil : selectedServingOption.unit)
                : "serving",
            selectedServingQuantity: servingSizeIsKnown
                ? (servingUnitOptions.isEmpty ? nil : selectedServingQuantity)
                : selectedServingQuantity,
            progressiveMeal: progressiveMeal,
            ingredients: scaledIngredients,
            productMetadata: productMetadata
        )
    }

}

struct IngredientEditorTarget: Identifiable {
    let id = UUID()
    let index: Int?
    let ingredient: MealIngredient
}

struct MealIngredientsSection: View {
    let ingredients: [MealIngredient]
    let onEdit: (Int) -> Void
    var onAdd: (() -> Void)? = nil
    var addMenu: AnyView? = nil

    var body: some View {
        Section {
            if ingredients.isEmpty {
                Text("No ingredient breakdown yet")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(ingredients.enumerated()), id: \.element.id) { index, ingredient in
                    Button {
                        onEdit(index)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Group {
                                    if let filename = ingredient.imageFilename,
                                       let data = FoodImageStore.shared.load(filename: filename),
                                       let image = UIImage(data: data) {
                                        Image(uiImage: image).resizable().scaledToFill()
                                    } else {
                                        Text(ingredient.emoji ?? "🍽️").font(.title2)
                                    }
                                }
                                .frame(width: 44, height: 44)
                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                .accessibilityHidden(true)
                                Text(ingredient.name)
                                    .font(.system(.body, design: .rounded, weight: .semibold))
                                    .foregroundStyle(.primary)
                                Spacer()
                                Text("\(MacroValueFormatter.string(ingredient.grams))g · \(ingredient.calories) kcal")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            HStack(spacing: 14) {
                                macro("P", ingredient.protein, AppColors.protein)
                                macro("C", ingredient.carbs, AppColors.carbs)
                                macro("F", ingredient.fat, AppColors.fat)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            if let addMenu {
                addMenu
            } else if let onAdd {
                Button(action: onAdd) {
                    Label("Add Ingredient", systemImage: "plus.circle.fill")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                }
                .tint(AppColors.calorie)
            }
        } header: {
            Text("Ingredients")
        } footer: {
            Text("Ingredient changes update the meal's calorie and macro totals automatically.")
        }
    }

    private func macro(_ label: String, _ value: Double, _ color: Color) -> some View {
        Text("\(label) \(MacroValueFormatter.string(value))g")
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
    }
}

struct IngredientEditorSheet: View {
    let target: IngredientEditorTarget
    let onSave: (MealIngredient) -> Void
    let onDelete: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var grams: String
    @State private var calories: String
    @State private var protein: String
    @State private var carbs: String
    @State private var fat: String
    @State private var nutritionBase: IngredientPortion

    init(target: IngredientEditorTarget, onSave: @escaping (MealIngredient) -> Void, onDelete: (() -> Void)?) {
        self.target = target
        self.onSave = onSave
        self.onDelete = onDelete
        _nutritionBase = State(initialValue: IngredientPortion(
            grams: target.ingredient.grams, calories: Double(target.ingredient.calories),
            protein: target.ingredient.protein, carbs: target.ingredient.carbs, fat: target.ingredient.fat
        ))
        _name = State(initialValue: target.ingredient.name)
        _grams = State(initialValue: MacroValueFormatter.string(target.ingredient.grams))
        _calories = State(initialValue: String(target.ingredient.calories))
        _protein = State(initialValue: MacroValueFormatter.string(target.ingredient.protein))
        _carbs = State(initialValue: MacroValueFormatter.string(target.ingredient.carbs))
        _fat = State(initialValue: MacroValueFormatter.string(target.ingredient.fat))
    }

    private var parsedIngredient: MealIngredient? {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty,
              let grams = decimal(grams), grams > 0, nutritionBase.resized(to: grams) != nil,
              let calories = decimal(calories), calories < Double(Int32.max),
              let protein = decimal(protein),
              let carbs = decimal(carbs),
              let fat = decimal(fat)
        else { return nil }
        return MealIngredient(
            id: target.ingredient.id,
            name: trimmedName,
            grams: grams,
            calories: Int(round(calories)),
            protein: protein,
            carbs: carbs,
            fat: fat,
            imageFilename: target.ingredient.imageFilename,
            additionalImageFilenames: target.ingredient.additionalImageFilenames,
            emoji: target.ingredient.emoji
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Ingredient") {
                    TextField("Name", text: $name)
                    valueRow("Weight", text: Binding(get: { grams }, set: changeWeight), unit: "g")
                    valueRow("Calories", text: nutritionBinding($calories), unit: "kcal")
                }
                Section("Macros") {
                    valueRow("Protein", text: nutritionBinding($protein), unit: "g")
                    valueRow("Carbs", text: nutritionBinding($carbs), unit: "g")
                    valueRow("Fat", text: nutritionBinding($fat), unit: "g")
                }
                if let onDelete {
                    Section {
                        Button("Remove Ingredient", role: .destructive) {
                            onDelete()
                            dismiss()
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(target.index == nil ? "Add Ingredient" : "Edit Ingredient")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        guard let ingredient = parsedIngredient else { return }
                        onSave(ingredient)
                        dismiss()
                    }
                    .disabled(parsedIngredient == nil)
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .tint(AppColors.calorie)
                }
            }
        }
    }

    private func changeWeight(_ text: String) {
        grams = text
        guard let weight = decimal(text), let scaled = nutritionBase.resized(to: weight) else { return }
        calories = String(Int(round(scaled.calories)))
        protein = MacroValueFormatter.string(scaled.protein)
        carbs = MacroValueFormatter.string(scaled.carbs)
        fat = MacroValueFormatter.string(scaled.fat)
    }

    private func nutritionBinding(_ field: Binding<String>) -> Binding<String> {
        Binding(get: { field.wrappedValue }, set: { value in
            field.wrappedValue = value
            guard let weight = decimal(grams), weight > 0,
                  let energy = decimal(calories), let p = decimal(protein),
                  let c = decimal(carbs), let f = decimal(fat) else { return }
            nutritionBase = IngredientPortion(grams: weight, calories: energy, protein: p, carbs: c, fat: f)
        })
    }

    private func valueRow(_ label: String, text: Binding<String>, unit: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            TextField("0", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 110)
            Text(unit)
                .foregroundStyle(.secondary)
        }
    }

    private func decimal(_ value: String) -> Double? {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        guard let number = Double(normalized), number.isFinite, number >= 0 else { return nil }
        return number
    }
}

private struct WhatIfMealImpactSheet: View {
    let entry: FoodEntry
    let dayEntries: [FoodEntry]
    let profile: UserProfile
    let weightMetric: Bool

    @Environment(\.dismiss) private var dismiss
    @State private var isLoadingSuggestion = true
    @State private var suggestion: String?
    @State private var suggestionError: String?

    private var currentTotals: WhatIfMacroTotals {
        WhatIfMacroTotals(entries: dayEntries)
    }

    private var mealTotals: WhatIfMacroTotals {
        WhatIfMacroTotals(entry: entry)
    }

    private var afterTotals: WhatIfMacroTotals {
        currentTotals + mealTotals
    }

    private var goals: WhatIfMacroTotals {
        WhatIfMacroTotals(
            calories: profile.effectiveCalories,
            protein: Double(profile.effectiveProtein),
            carbs: Double(profile.effectiveCarbs),
            fat: Double(profile.effectiveFat)
        )
    }

    private var suggestionTaskID: String {
        [
            entry.name,
            "\(entry.calories)",
            MacroValueFormatter.string(entry.protein),
            MacroValueFormatter.string(entry.carbs),
            MacroValueFormatter.string(entry.fat),
            "\(dayEntries.count)",
            "\(profile.effectiveCalories)",
            "\(profile.effectiveProtein)",
            "\(profile.effectiveCarbs)",
            "\(profile.effectiveFat)"
        ].joined(separator: "|")
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    WhatIfImpactRow(
                        label: "Calories",
                        added: "+\(entry.calories) kcal",
                        after: "\(afterTotals.calories) / \(goals.calories) kcal",
                        remaining: remainingCaloriesText,
                        isOver: afterTotals.calories > goals.calories,
                        tint: AppColors.calorie
                    )
                    WhatIfImpactRow(
                        label: "Protein",
                        added: "+\(MacroValueFormatter.withUnit(entry.protein))",
                        after: "\(MacroValueFormatter.string(afterTotals.protein)) / \(profile.effectiveProtein)g",
                        remaining: remainingMacroText(afterTotals.protein, goal: Double(profile.effectiveProtein)),
                        isOver: false,
                        tint: AppColors.protein
                    )
                    WhatIfImpactRow(
                        label: "Carbs",
                        added: "+\(MacroValueFormatter.withUnit(entry.carbs))",
                        after: "\(MacroValueFormatter.string(afterTotals.carbs)) / \(profile.effectiveCarbs)g",
                        remaining: remainingMacroText(afterTotals.carbs, goal: Double(profile.effectiveCarbs)),
                        isOver: afterTotals.carbs > Double(profile.effectiveCarbs),
                        tint: AppColors.carbs
                    )
                    WhatIfImpactRow(
                        label: "Fat",
                        added: "+\(MacroValueFormatter.withUnit(entry.fat))",
                        after: "\(MacroValueFormatter.string(afterTotals.fat)) / \(profile.effectiveFat)g",
                        remaining: remainingMacroText(afterTotals.fat, goal: Double(profile.effectiveFat)),
                        isOver: afterTotals.fat > Double(profile.effectiveFat),
                        tint: AppColors.fat
                    )
                } header: {
                    Text("Impact on Today")
                } footer: {
                    Text("This does not log the meal. It shows what today would look like if you logged \(entry.name).")
                }

                Section("AI Suggestion") {
                    if isLoadingSuggestion {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Checking fit with your goals...")
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    } else if let suggestion {
                        Text(suggestion)
                            .font(.body)
                            .foregroundStyle(.primary)
                            .textSelection(.enabled)
                    } else if let suggestionError {
                        VStack(alignment: .leading, spacing: 10) {
                            Text(suggestionError)
                                .foregroundStyle(.secondary)
                            Button("Retry") {
                                Task { await loadSuggestion() }
                            }
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .tint(AppColors.calorie)
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("What if?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .tint(AppColors.calorie)
                }
            }
            .task(id: suggestionTaskID) {
                await loadSuggestion()
            }
        }
    }

    private var remainingCaloriesText: String {
        let remaining = goals.calories - afterTotals.calories
        if remaining >= 0 {
            return "\(remaining) kcal left"
        }
        return "\(abs(remaining)) kcal over"
    }

    private func remainingMacroText(_ value: Double, goal: Double) -> String {
        let remaining = goal - value
        if remaining >= 0 {
            return "\(MacroValueFormatter.string(remaining))g left"
        }
        return "\(MacroValueFormatter.string(abs(remaining)))g over"
    }

    @MainActor
    private func loadSuggestion() async {
        isLoadingSuggestion = true
        suggestion = nil
        suggestionError = nil

        do {
            let text = try await GeminiService.suggestMealWhatIf(
                entry: entry,
                dayEntries: dayEntries,
                profile: profile,
                weightMetric: weightMetric
            )
            suggestion = text.isEmpty ? "No suggestion returned. You can still review the numbers above before logging." : text
        } catch {
            suggestionError = error.localizedDescription
        }

        isLoadingSuggestion = false
    }
}

private struct WhatIfImpactRow: View {
    let label: String
    let added: String
    let after: String
    let remaining: String
    let isOver: Bool
    let tint: Color

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(tint.opacity(0.18))
                .frame(width: 30, height: 30)
                .overlay {
                    Circle()
                        .fill(tint)
                        .frame(width: 10, height: 10)
                }

            VStack(alignment: .leading, spacing: 3) {
                Text(LocalizedDisplayText.text(label))
                    .font(.system(.body, design: .rounded, weight: .semibold))
                Text(after)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 3) {
                Text(added)
                    .font(.system(.body, design: .rounded, weight: .semibold))
                Text(remaining)
                    .font(.caption)
                    .foregroundStyle(isOver ? Color.red : .secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct WhatIfMacroTotals {
    var calories: Int
    var protein: Double
    var carbs: Double
    var fat: Double

    static let zero = WhatIfMacroTotals(calories: 0, protein: 0, carbs: 0, fat: 0)

    init(calories: Int, protein: Double, carbs: Double, fat: Double) {
        self.calories = calories
        self.protein = protein
        self.carbs = carbs
        self.fat = fat
    }

    init(entry: FoodEntry) {
        self.init(
            calories: entry.calories,
            protein: entry.protein,
            carbs: entry.carbs,
            fat: entry.fat
        )
    }

    init(entries: [FoodEntry]) {
        self = entries.reduce(.zero) { totals, entry in
            totals + WhatIfMacroTotals(entry: entry)
        }
    }

    static func + (lhs: WhatIfMacroTotals, rhs: WhatIfMacroTotals) -> WhatIfMacroTotals {
        WhatIfMacroTotals(
            calories: lhs.calories + rhs.calories,
            protein: lhs.protein + rhs.protein,
            carbs: lhs.carbs + rhs.carbs,
            fat: lhs.fat + rhs.fat
        )
    }
}

struct KeyboardDismissTapInstaller: UIViewRepresentable {
    func makeUIView(context: Context) -> KeyboardDismissTapView {
        KeyboardDismissTapView()
    }

    func updateUIView(_ uiView: KeyboardDismissTapView, context: Context) {
        uiView.installIfNeeded()
    }

    static func dismantleUIView(_ uiView: KeyboardDismissTapView, coordinator: ()) {
        uiView.removeGesture()
    }
}

final class KeyboardDismissTapView: UIView, UIGestureRecognizerDelegate {
    private weak var installedWindow: UIWindow?
    private var tapGesture: UITapGestureRecognizer?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        installIfNeeded()
    }

    func installIfNeeded() {
        guard let window, installedWindow !== window else { return }
        removeGesture()

        let gesture = UITapGestureRecognizer(target: self, action: #selector(handleTap))
        gesture.cancelsTouchesInView = false
        gesture.delegate = self
        window.addGestureRecognizer(gesture)

        installedWindow = window
        tapGesture = gesture
    }

    func removeGesture() {
        if let tapGesture, let installedWindow {
            installedWindow.removeGestureRecognizer(tapGesture)
        }
        tapGesture = nil
        installedWindow = nil
    }

    @objc private func handleTap() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        !touch.viewContainsInputOrControl
    }
}

struct EndEditingDecimalTextField: UIViewRepresentable {
    @Binding var text: String
    let focusRequest: Int
    var onEditingChanged: (Bool) -> Void
    var keyboardType: UIKeyboardType = .decimalPad
    var placeholder: String = "0"
    var accessibilityLabel: String? = nil
    var showsCalculatorToolbar = false

    func makeUIView(context: Context) -> UITextField {
        let textField = UITextField()
        textField.delegate = context.coordinator
        textField.keyboardType = keyboardType
        textField.textAlignment = .right
        textField.placeholder = placeholder
        textField.accessibilityLabel = accessibilityLabel
        textField.font = .preferredFont(forTextStyle: .body)
        textField.adjustsFontForContentSizeCategory = true
        context.coordinator.textField = textField
        textField.inputAccessoryView = context.coordinator.makeInputAccessoryView()
        textField.addTarget(context.coordinator, action: #selector(Coordinator.textDidChange(_:)), for: .editingChanged)
        return textField
    }

    func updateUIView(_ textField: UITextField, context: Context) {
        if textField.text != text {
            textField.text = text
        }
        textField.keyboardType = keyboardType
        textField.placeholder = placeholder
        textField.accessibilityLabel = accessibilityLabel
        context.coordinator.refreshPreview(for: text)
        if context.coordinator.lastFocusRequest != focusRequest {
            context.coordinator.lastFocusRequest = focusRequest
            DispatchQueue.main.async {
                textField.becomeFirstResponder()
                Self.moveCaretToEnd(in: textField)
            }
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(
            text: $text,
            focusRequest: focusRequest,
            onEditingChanged: onEditingChanged,
            showsCalculatorToolbar: showsCalculatorToolbar
        )
    }

    private static func moveCaretToEnd(in textField: UITextField) {
        let end = textField.endOfDocument
        textField.selectedTextRange = textField.textRange(from: end, to: end)
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        @Binding private var text: String
        var lastFocusRequest: Int
        private let onEditingChanged: (Bool) -> Void
        private let showsCalculatorToolbar: Bool
        weak var textField: UITextField?
        private weak var equalsButton: UIButton?

        init(
            text: Binding<String>,
            focusRequest: Int,
            onEditingChanged: @escaping (Bool) -> Void,
            showsCalculatorToolbar: Bool
        ) {
            self._text = text
            self.lastFocusRequest = focusRequest
            self.onEditingChanged = onEditingChanged
            self.showsCalculatorToolbar = showsCalculatorToolbar
        }

        @objc func textDidChange(_ textField: UITextField) {
            text = textField.text ?? ""
            refreshPreview(for: text)
        }

        func makeInputAccessoryView() -> UIView {
            if showsCalculatorToolbar {
                return makeCalculatorAccessoryView()
            }

            let doneItem = UIBarButtonItem(title: "Done", style: .plain, target: self, action: #selector(doneTapped))
            doneItem.tintColor = Self.calorieTint

            let toolbar = UIToolbar()
            toolbar.items = [flexibleSpace(), doneItem]
            toolbar.sizeToFit()
            return toolbar
        }

        private func makeCalculatorAccessoryView() -> UIView {
            let accessory = UIView()
            accessory.frame = CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: 52)
            accessory.autoresizingMask = [.flexibleWidth]
            accessory.backgroundColor = .clear

            let material: UIVisualEffect
            if #available(iOS 26.0, *) {
                let glass = UIGlassEffect(style: .regular)
                glass.tintColor = Self.calorieTint.withAlphaComponent(0.08)
                material = glass
            } else {
                material = UIBlurEffect(style: .systemChromeMaterial)
            }
            let glassSurface = UIVisualEffectView(effect: material)
            glassSurface.clipsToBounds = true
            glassSurface.layer.cornerRadius = 16
            glassSurface.layer.cornerCurve = .continuous
            glassSurface.translatesAutoresizingMaskIntoConstraints = false
            accessory.addSubview(glassSurface)

            let clear = button("C", action: #selector(clearTapped), accessibilityLabel: "Clear")
            let add = button("+", action: #selector(addTapped), accessibilityLabel: "Add")
            let subtract = button("−", action: #selector(subtractTapped), accessibilityLabel: "Subtract")
            let multiply = button("×", action: #selector(multiplyTapped), accessibilityLabel: "Multiply")
            let divide = button("÷", action: #selector(divideTapped), accessibilityLabel: "Divide")
            let equals = button("=", action: #selector(equalsTapped), accessibilityLabel: "Equals")
            let done = button("Done", action: #selector(doneTapped), accessibilityLabel: "Done", emphasized: true)
            equalsButton = equals

            let stack = UIStackView(arrangedSubviews: [clear, add, subtract, multiply, divide, equals, done])
            stack.axis = .horizontal
            stack.alignment = .fill
            stack.distribution = .fillEqually
            stack.spacing = 5
            stack.translatesAutoresizingMaskIntoConstraints = false
            glassSurface.contentView.addSubview(stack)
            NSLayoutConstraint.activate([
                glassSurface.leadingAnchor.constraint(equalTo: accessory.leadingAnchor, constant: 8),
                glassSurface.trailingAnchor.constraint(equalTo: accessory.trailingAnchor, constant: -8),
                glassSurface.topAnchor.constraint(equalTo: accessory.topAnchor, constant: 4),
                glassSurface.bottomAnchor.constraint(equalTo: accessory.bottomAnchor, constant: -4),
                stack.leadingAnchor.constraint(equalTo: glassSurface.contentView.leadingAnchor, constant: 5),
                stack.trailingAnchor.constraint(equalTo: glassSurface.contentView.trailingAnchor, constant: -5),
                stack.topAnchor.constraint(equalTo: glassSurface.contentView.topAnchor, constant: 4),
                stack.bottomAnchor.constraint(equalTo: glassSurface.contentView.bottomAnchor, constant: -4)
            ])
            return accessory
        }

        @objc func doneTapped() {
            collapseExpressionIfValid()
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }

        @objc private func clearTapped() {
            replaceText(with: "")
        }

        @objc private func addTapped() { append(operation: "+") }
        @objc private func subtractTapped() { append(operation: "−") }
        @objc private func multiplyTapped() { append(operation: "×") }
        @objc private func divideTapped() { append(operation: "÷") }

        @objc private func equalsTapped() {
            collapseExpressionIfValid()
        }

        func textFieldDidBeginEditing(_ textField: UITextField) {
            onEditingChanged(true)
            DispatchQueue.main.async {
                EndEditingDecimalTextField.moveCaretToEnd(in: textField)
            }
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            onEditingChanged(false)
        }

        func refreshPreview(for expression: String) {
            guard showsCalculatorToolbar,
                  ServingAmountExpression.containsOperation(expression),
                  let result = ServingAmountExpression.evaluate(expression),
                  result > 0
            else {
                equalsButton?.setTitle("=", for: .normal)
                return
            }
            equalsButton?.setTitle("= \(ServingUnitEditor.formatQuantity(result))", for: .normal)
        }

        private func append(operation: Character) {
            replaceText(with: ServingAmountExpression.appending(operation, to: text))
        }

        private func collapseExpressionIfValid() {
            guard let result = ServingAmountExpression.evaluate(text), result > 0 else { return }
            replaceText(with: ServingUnitEditor.formatQuantity(result))
        }

        private func replaceText(with newValue: String) {
            text = newValue
            textField?.text = newValue
            refreshPreview(for: newValue)
            if let textField {
                EndEditingDecimalTextField.moveCaretToEnd(in: textField)
            }
        }

        private func button(
            _ title: String,
            action: Selector,
            accessibilityLabel: String,
            emphasized: Bool = false
        ) -> UIButton {
            let button = UIButton(type: .system)
            button.setTitle(title, for: .normal)
            button.setTitleColor(emphasized ? .white : Self.calorieTint, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
            button.titleLabel?.adjustsFontSizeToFitWidth = true
            button.titleLabel?.minimumScaleFactor = 0.72
            button.backgroundColor = emphasized ? Self.calorieTint : .clear
            button.layer.cornerRadius = 10
            button.accessibilityLabel = accessibilityLabel
            button.addTarget(self, action: action, for: .touchUpInside)
            return button
        }

        private func flexibleSpace() -> UIBarButtonItem {
            UIBarButtonItem(systemItem: .flexibleSpace)
        }

        private static let calorieTint = UIColor(red: 1.0, green: 55.0 / 255.0, blue: 95.0 / 255.0, alpha: 1.0)
    }
}

private extension UITouch {
    var viewContainsInputOrControl: Bool {
        var currentView = view
        while let view = currentView {
            if view is UITextField || view is UITextView || view is UIControl {
                return true
            }
            currentView = view.superview
        }
        return false
    }
}

private struct ReviewNutritionValueRow: View {
    let label: String
    let displayValue: String
    let editValue: String
    let unit: String
    let isUnlocked: Bool
    var dim = false
    let onEdit: (String) -> Void

    @State private var draft = ""
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack {
            Text(LocalizedDisplayText.text(label))
                .foregroundStyle(dim ? .secondary : .primary)
            Spacer()
            if isUnlocked {
                TextField("0", text: Binding(
                    get: { isFocused ? draft : editValue },
                    set: { newValue in
                        draft = newValue
                        onEdit(newValue)
                    }
                ))
                .focused($isFocused)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .fontWeight(.medium)
                .frame(minWidth: 76, maxWidth: 118)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(AppColors.calorie.opacity(0.10), in: RoundedRectangle(cornerRadius: 9))
                .onAppear { draft = editValue }
                .onChange(of: editValue) { _, newValue in
                    if !isFocused {
                        draft = newValue
                    }
                }
                .onChange(of: isUnlocked) { _, unlocked in
                    if unlocked {
                        draft = editValue
                    } else {
                        isFocused = false
                    }
                }
            } else {
                Text(displayValue)
                    .fontWeight(.medium)
            }
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(width: 36, alignment: .leading)
        }
    }
}

struct NutritionDisplayRow: View {
    let label: String
    let value: String
    let unit: String

    var body: some View {
        HStack {
            Text(LocalizedDisplayText.text(label))
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
            Text(LocalizedDisplayText.text(label))
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
