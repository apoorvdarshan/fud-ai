import SwiftUI

struct EditFoodEntryView: View {
    private enum ScrollTarget: Hashable {
        case quantity
    }

    let entry: FoodEntry
    @Environment(FoodStore.self) private var foodStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(\.dismiss) private var dismiss

    // Base values (the entry's nutrition at its logged serving size)
    @State private var baseCalories: Int
    @State private var baseProtein: Double
    @State private var baseCarbs: Double
    @State private var baseFat: Double
    @State private var baseServingSizeGrams: Double
    @State private var baseSugar: Double?
    @State private var baseAddedSugar: Double?
    @State private var baseFiber: Double?
    @State private var baseSaturatedFat: Double?
    @State private var baseMonounsaturatedFat: Double?
    @State private var basePolyunsaturatedFat: Double?
    @State private var baseCholesterol: Double?
    @State private var baseCaffeine: Double?
    @State private var baseSupplementalNutrients: [String: Double]
    @State private var baseSodium: Double?
    @State private var basePotassium: Double?
    @State private var baseTransFat: Double?
    @State private var baseCalcium: Double?
    @State private var baseIron: Double?
    @State private var baseMagnesium: Double?
    @State private var baseZinc: Double?
    @State private var baseVitaminA: Double?
    @State private var baseVitaminC: Double?
    @State private var baseVitaminD: Double?
    @State private var baseVitaminB12: Double?
    @State private var baseVitaminE: Double?
    @State private var baseVitaminK: Double?
    @State private var baseFolate: Double?
    @State private var baseOmega3: Double?
    @State private var baseIngredients: [MealIngredient]
    @State private var servingUnitOptions: [ServingUnitOption]
    @State private var servingSizeIsKnown: Bool
    @State private var ingredientEditor: IngredientEditorTarget?

    @State private var emoji: String?
    @State private var customNote: String
    @State private var savedNote: String
    @State private var isReprocessing: Bool = false
    @State private var reprocessingError: String? = nil
    @State private var isDeleteConfirmationPresented = false
    @State private var removedPhotoIDs = Set<FoodEntryPhoto.ID>()
    @State private var imagePreview: FullScreenImagePreview?

    @State private var name: String
    @State private var servingSizeGrams: Double
    @State private var servingSizeText: String
    @State private var selectedServingUnitID: String
    @State private var quantityFocusRequest = 0
    @State private var isQuantityEditing = false
    @State private var mealType: MealType
    @State private var loggedAt: Date

    private var scale: Double {
        guard baseServingSizeGrams > 0 else { return 1 }
        return servingSizeGrams / baseServingSizeGrams
    }

    private var scaledCalories: Int { Int(round(Double(baseCalories) * scale)) }
    private var scaledProtein: Double { baseProtein * scale }
    private var scaledCarbs: Double { baseCarbs * scale }
    private var scaledFat: Double { baseFat * scale }
    private var scaledSugar: Double? { baseSugar.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledAddedSugar: Double? { baseAddedSugar.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledFiber: Double? { baseFiber.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledSaturatedFat: Double? { baseSaturatedFat.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledMonounsaturatedFat: Double? { baseMonounsaturatedFat.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledPolyunsaturatedFat: Double? { basePolyunsaturatedFat.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledCholesterol: Double? { baseCholesterol.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledCaffeine: Double? { baseCaffeine.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledSupplementalNutrients: [String: Double] {
        baseSupplementalNutrients.mapValues { scale == 1 ? $0 : round($0 * scale * 10) / 10 }
    }
    private var scaledSodium: Double? { baseSodium.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledPotassium: Double? { basePotassium.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledTransFat: Double? { baseTransFat.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledCalcium: Double? { baseCalcium.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledIron: Double? { baseIron.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledMagnesium: Double? { baseMagnesium.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledZinc: Double? { baseZinc.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminA: Double? { baseVitaminA.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminC: Double? { baseVitaminC.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminD: Double? { baseVitaminD.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminB12: Double? { baseVitaminB12.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminE: Double? { baseVitaminE.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledVitaminK: Double? { baseVitaminK.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledFolate: Double? { baseFolate.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledOmega3: Double? { baseOmega3.map { scale == 1 ? $0 : round($0 * scale * 10) / 10 } }
    private var scaledIngredients: [MealIngredient] {
        baseIngredients.map { $0.removingPhotos(withIDs: removedPhotoIDs).scaled(by: scale) }
    }
    private var selectedServingOption: ServingUnitOption {
        ServingUnitOption.option(matching: selectedServingUnitID, in: servingUnitOptions)
    }
    private var selectedServingQuantity: Double? {
        ServingAmountExpression.evaluate(servingSizeText)
    }

    private var entryWithEditedIngredients: FoodEntry {
        var edited = entry
        edited.ingredients = baseIngredients
        return edited
    }

    private var visiblePhotos: [FoodEntryPhoto] {
        entryWithEditedIngredients.editablePhotos.filter { !removedPhotoIDs.contains($0.id) }
    }

    init(entry: FoodEntry) {
        self.entry = entry
        let serving = entry.reviewServingReference
        let normalizedServingUnitOptions = entry.hasKnownServingSize
            ? ServingUnitOption.normalizedOptions(entry.servingUnitOptions, totalGrams: serving)
            : entry.reviewServingUnitOptions
        let initialServingUnitID = ServingUnitOption.initialUnitID(
            preferredUnit: entry.reviewSelectedServingUnit,
            options: normalizedServingUnitOptions,
            defaultToGrams: entry.hasKnownServingSize && FoodMeasurementSettings.preferGramsByDefault
        )
        self._baseCalories = State(initialValue: entry.calories)
        self._baseProtein = State(initialValue: entry.protein)
        self._baseCarbs = State(initialValue: entry.carbs)
        self._baseFat = State(initialValue: entry.fat)
        self._baseServingSizeGrams = State(initialValue: serving)
        self._baseSugar = State(initialValue: entry.sugar)
        self._baseAddedSugar = State(initialValue: entry.addedSugar)
        self._baseFiber = State(initialValue: entry.fiber)
        self._baseSaturatedFat = State(initialValue: entry.saturatedFat)
        self._baseMonounsaturatedFat = State(initialValue: entry.monounsaturatedFat)
        self._basePolyunsaturatedFat = State(initialValue: entry.polyunsaturatedFat)
        self._baseCholesterol = State(initialValue: entry.cholesterol)
        self._baseCaffeine = State(initialValue: entry.caffeine)
        self._baseSupplementalNutrients = State(initialValue: entry.supplementalNutrients)
        self._baseSodium = State(initialValue: entry.sodium)
        self._basePotassium = State(initialValue: entry.potassium)
        self._baseTransFat = State(initialValue: entry.transFat)
        self._baseCalcium = State(initialValue: entry.calcium)
        self._baseIron = State(initialValue: entry.iron)
        self._baseMagnesium = State(initialValue: entry.magnesium)
        self._baseZinc = State(initialValue: entry.zinc)
        self._baseVitaminA = State(initialValue: entry.vitaminA)
        self._baseVitaminC = State(initialValue: entry.vitaminC)
        self._baseVitaminD = State(initialValue: entry.vitaminD)
        self._baseVitaminB12 = State(initialValue: entry.vitaminB12)
        self._baseVitaminE = State(initialValue: entry.vitaminE)
        self._baseVitaminK = State(initialValue: entry.vitaminK)
        self._baseFolate = State(initialValue: entry.folate)
        self._baseOmega3 = State(initialValue: entry.omega3)
        self._baseIngredients = State(initialValue: entry.ingredients)
        self._servingUnitOptions = State(initialValue: normalizedServingUnitOptions)
        self._servingSizeIsKnown = State(initialValue: entry.hasKnownServingSize)
        self._emoji = State(initialValue: entry.emoji)
        self._customNote = State(initialValue: entry.customNote ?? "")
        self._savedNote = State(initialValue: entry.customNote ?? "")
        self._name = State(initialValue: entry.name)
        self._servingSizeGrams = State(initialValue: serving)
        self._servingSizeText = State(initialValue: ServingUnitOption.initialQuantityText(
            totalGrams: serving,
            selectedUnitID: initialServingUnitID,
            selectedQuantity: entry.reviewSelectedServingQuantity,
            options: normalizedServingUnitOptions
        ))
        self._selectedServingUnitID = State(initialValue: initialServingUnitID)
        self._mealType = State(initialValue: entry.mealType)
        self._loggedAt = State(initialValue: entry.timestamp)
    }

    private static func formatGrams(_ value: Double) -> String {
        if value == value.rounded() {
            return String(Int(value))
        }
        return String(format: "%.1f", value)
    }

    private func applyIngredientChanges(_ displayedIngredients: [MealIngredient]) {
        baseIngredients = displayedIngredients
        let totals = displayedIngredients.ingredientTotals
        baseCalories = totals.calories
        baseProtein = totals.protein
        baseCarbs = totals.carbs
        baseFat = totals.fat
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
                    let photos = visiblePhotos
                    if !photos.isEmpty {
                        Section {
                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 12) {
                                    ForEach(Array(photos.enumerated()), id: \.element.id) { index, photo in
                                        Group {
                                            if let image = photo.data.flatMap(UIImage.init(data:)) {
                                                Image(uiImage: image)
                                                    .resizable()
                                                    .scaledToFill()
                                            } else {
                                                Image(systemName: "photo")
                                                    .font(.largeTitle)
                                                    .foregroundStyle(.secondary)
                                            }
                                        }
                                        .frame(width: 220, height: 200)
                                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                                        .onTapGesture {
                                            let previewImages = photos.compactMap { $0.data.flatMap(UIImage.init(data:)) }
                                            guard !previewImages.isEmpty else { return }
                                            imagePreview = FullScreenImagePreview(
                                                images: previewImages,
                                                initialIndex: min(index, previewImages.count - 1)
                                            )
                                        }
                                        .accessibilityAddTraits(.isButton)
                                        .accessibilityLabel("View full photo")
                                        .overlay(alignment: .topTrailing) {
                                            Button {
                                                removedPhotoIDs.insert(photo.id)
                                            } label: {
                                                Image(systemName: "xmark")
                                                    .font(.system(size: 14, weight: .bold))
                                                    .foregroundStyle(.white)
                                                    .frame(width: 32, height: 32)
                                                    .background(.black.opacity(0.6), in: Circle())
                                                    .frame(width: 44, height: 44)
                                                    .contentShape(Rectangle())
                                            }
                                            .buttonStyle(.plain)
                                            .accessibilityLabel("Remove photo")
                                            .padding(4)
                                        }
                                        .overlay(alignment: .bottomTrailing) {
                                            if photos.count > 1 {
                                                Text("\(index + 1)/\(photos.count)")
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
                    } else if let emoji = emoji {
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

                    if let productMetadata = entry.productMetadata,
                       productMetadata.hasDisplayDetails {
                        FoodProductMetadataSection(
                            metadata: productMetadata,
                            allergenAnalysis: entry.allergenAnalysis(
                                for: profileStore.profile.configuredAllergenSensitivities
                            )
                        )
                    }
                    if !(entry.productMetadata?.hasDisplayDetails ?? false),
                       !profileStore.profile.configuredAllergenSensitivities.isEmpty {
                        Section("Allergen Check") {
                            Text(
                                entry.allergenAnalysis(
                                    for: profileStore.profile.configuredAllergenSensitivities
                                ).summary
                            )
                            .foregroundStyle(.secondary)
                            Text("Check the package label and follow your clinician's advice.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
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

                    Section("Nutrition") {
                        NutritionDisplayRow(label: "Calories", value: "\(scaledCalories)", unit: "kcal")
                        NutritionDisplayRow(label: "Protein", value: MacroValueFormatter.string(scaledProtein), unit: "g")
                        NutritionDisplayRow(label: "Carbs", value: MacroValueFormatter.string(scaledCarbs), unit: "g")
                        NutritionDisplayRow(label: "Fat", value: MacroValueFormatter.string(scaledFat), unit: "g")
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
                            OptionalNutritionDisplayRow(label: "Sugar", value: scaledSugar, unit: "g")
                            OptionalNutritionDisplayRow(label: "Added Sugar", value: scaledAddedSugar, unit: "g")
                            OptionalNutritionDisplayRow(label: "Fiber", value: scaledFiber, unit: "g")
                            OptionalNutritionDisplayRow(label: "Saturated Fat", value: scaledSaturatedFat, unit: "g")
                            OptionalNutritionDisplayRow(label: "Mono Fat", value: scaledMonounsaturatedFat, unit: "g")
                            OptionalNutritionDisplayRow(label: "Poly Fat", value: scaledPolyunsaturatedFat, unit: "g")
                            OptionalNutritionDisplayRow(label: "Cholesterol", value: scaledCholesterol, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Caffeine", value: scaledCaffeine, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Sodium", value: scaledSodium, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Potassium", value: scaledPotassium, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Trans Fat", value: scaledTransFat, unit: "g")
                            OptionalNutritionDisplayRow(label: "Calcium", value: scaledCalcium, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Iron", value: scaledIron, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Magnesium", value: scaledMagnesium, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Zinc", value: scaledZinc, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Vitamin A", value: scaledVitaminA, unit: "mcg")
                            OptionalNutritionDisplayRow(label: "Vitamin C", value: scaledVitaminC, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Vitamin D", value: scaledVitaminD, unit: "mcg")
                            OptionalNutritionDisplayRow(label: "Vitamin B12", value: scaledVitaminB12, unit: "mcg")
                            OptionalNutritionDisplayRow(label: "Vitamin E", value: scaledVitaminE, unit: "mg")
                            OptionalNutritionDisplayRow(label: "Vitamin K", value: scaledVitaminK, unit: "mcg")
                            OptionalNutritionDisplayRow(label: "Folate", value: scaledFolate, unit: "mcg")
                            OptionalNutritionDisplayRow(label: "Omega-3", value: scaledOmega3, unit: "g")
                            ForEach(SupplementalNutrient.allCases) { nutrient in
                                OptionalNutritionDisplayRow(
                                    label: nutrient.displayName,
                                    value: scaledSupplementalNutrients[nutrient.rawValue],
                                    unit: "g"
                                )
                            }
                        }
                        .tint(AppColors.calorie)
                    }

                    Section("Reprocess with AI") {
                        ZStack(alignment: .topLeading) {
                            if customNote.isEmpty {
                                Text("Add a note to refine this entry — e.g. “large bowl, extra olive oil” — then tap Reprocess.")
                                    .foregroundStyle(.tertiary)
                                    .padding(.top, 8)
                                    .padding(.leading, 5)
                                    .allowsHitTesting(false)
                            }
                            TextEditor(text: $customNote)
                                .frame(minHeight: 80)
                        }

                        if let errorMsg = reprocessingError {
                            Text(errorMsg)
                                .font(.caption)
                                .foregroundStyle(.red)
                        }
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

                    Section("Date & Time") {
                        DatePicker("Date", selection: $loggedAt, displayedComponents: .date)
                            .tint(AppColors.calorie)
                        DatePicker("Time", selection: $loggedAt, displayedComponents: .hourAndMinute)
                            .tint(AppColors.calorie)
                    }

                    Section("Actions") {
                        Button {
                            withAnimation(.snappy) {
                                foodStore.toggleFavorite(entry)
                            }
                        } label: {
                            Label(
                                foodStore.isFavorite(entry) ? "Remove from Favorites" : "Save to Favorites",
                                systemImage: foodStore.isFavorite(entry) ? "heart.slash.fill" : "heart.fill"
                            )
                        }
                        .tint(AppColors.calorie)

                        Button(role: .destructive) {
                            isDeleteConfirmationPresented = true
                        } label: {
                            Label("Delete Food Log", systemImage: "trash.fill")
                        }
                    }

                    // Share this meal as a fudai://add-meal link (issue #107)
                    Section {
                        Button {
                            MealShare.presentShareSheet(for: [entry])
                        } label: {
                            Label("Share Meal", systemImage: "square.and.arrow.up")
                                .font(.system(.body, design: .rounded, weight: .medium))
                        }
                        .tint(AppColors.calorie)
                    } footer: {
                        Text("Send this meal to a friend — they can add it to their Fud AI in one tap.")
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
                .disabled(isReprocessing)
                .navigationTitle("Edit Food")
                .navigationBarTitleDisplayMode(.inline)
                .alert("Delete Food Log?", isPresented: $isDeleteConfirmationPresented) {
                    Button("Delete", role: .destructive) {
                        foodStore.deleteEntry(entry)
                        dismiss()
                    }
                    Button("Cancel", role: .cancel) { }
                } message: {
                    Text("This removes the food from your diary. Saved favorites are kept.")
                }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if isReprocessing {
                            ProgressView()
                        } else if noteChanged {
                            Button("Reprocess", action: reprocess)
                                .font(.system(.body, design: .rounded, weight: .semibold))
                                .tint(AppColors.calorie)
                        } else {
                            Button("Save", action: saveChanges)
                                .font(.system(.body, design: .rounded, weight: .semibold))
                                .tint(AppColors.calorie)
                        }
                    }
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
                .fullScreenImagePreview($imagePreview)
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

    private var noteChanged: Bool {
        customNote.trimmingCharacters(in: .whitespacesAndNewlines) != savedNote
    }

    /// Re-run the AI on this entry with the edited note and overwrite the fields in
    /// place, then mark the note as saved (so the toolbar reverts to "Save").
    private func reprocess() {
        Task {
            isReprocessing = true
            reprocessingError = nil
            do {
                let editedEntry = entryWithEditedIngredients.removingPhotos(withIDs: removedPhotoIDs)
                let newAnalysis = try await foodStore.reprocessEntry(editedEntry, withNote: customNote)

                name = newAnalysis.name
                baseCalories = newAnalysis.calories
                baseProtein = newAnalysis.protein
                baseCarbs = newAnalysis.carbs
                baseFat = newAnalysis.fat
                baseServingSizeGrams = newAnalysis.servingSizeGrams
                servingSizeIsKnown = newAnalysis.servingSizeIsKnown
                baseSugar = newAnalysis.sugar
                baseAddedSugar = newAnalysis.addedSugar
                baseFiber = newAnalysis.fiber
                baseSaturatedFat = newAnalysis.saturatedFat
                baseMonounsaturatedFat = newAnalysis.monounsaturatedFat
                basePolyunsaturatedFat = newAnalysis.polyunsaturatedFat
                baseCholesterol = newAnalysis.cholesterol
                baseCaffeine = newAnalysis.caffeine
                baseSupplementalNutrients = newAnalysis.supplementalNutrients
                baseSodium = newAnalysis.sodium
                basePotassium = newAnalysis.potassium
                baseTransFat = newAnalysis.transFat
                baseCalcium = newAnalysis.calcium
                baseIron = newAnalysis.iron
                baseMagnesium = newAnalysis.magnesium
                baseZinc = newAnalysis.zinc
                baseVitaminA = newAnalysis.vitaminA
                baseVitaminC = newAnalysis.vitaminC
                baseVitaminD = newAnalysis.vitaminD
                baseVitaminB12 = newAnalysis.vitaminB12
                baseVitaminE = newAnalysis.vitaminE
                baseVitaminK = newAnalysis.vitaminK
                baseFolate = newAnalysis.folate
                baseOmega3 = newAnalysis.omega3
                baseIngredients = newAnalysis.ingredients
                emoji = newAnalysis.emoji

                servingUnitOptions = newAnalysis.servingSizeIsKnown
                    ? ServingUnitOption.normalizedOptions(
                        newAnalysis.servingUnitOptions,
                        totalGrams: newAnalysis.servingSizeGrams
                    )
                    : [.loggedServing(quantity: newAnalysis.selectedServingQuantity ?? 1)]
                let initialServingUnitID = ServingUnitOption.initialUnitID(
                    preferredUnit: newAnalysis.servingSizeIsKnown
                        ? newAnalysis.selectedServingUnit
                        : "serving",
                    options: servingUnitOptions,
                    defaultToGrams: newAnalysis.servingSizeIsKnown && FoodMeasurementSettings.preferGramsByDefault
                )
                selectedServingUnitID = initialServingUnitID
                servingSizeGrams = newAnalysis.servingSizeGrams
                servingSizeText = ServingUnitOption.initialQuantityText(
                    totalGrams: newAnalysis.servingSizeGrams,
                    selectedUnitID: initialServingUnitID,
                    selectedQuantity: newAnalysis.selectedServingQuantity,
                    options: servingUnitOptions
                )

                savedNote = customNote.trimmingCharacters(in: .whitespacesAndNewlines)
            } catch {
                reprocessingError = GeminiService.analysisErrorMessage(error)
            }
            isReprocessing = false
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
            timestamp: loggedAt,
            imageData: entry.imageData,
            imageFilename: entry.imageFilename,
            additionalImageData: entry.additionalImageData,
            additionalImageFilenames: entry.additionalImageFilenames,
            emoji: emoji,
            source: entry.source,
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
            customNote: customNote.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : customNote,
            progressiveMeal: entry.progressiveMeal,
            ingredients: scaledIngredients,
            productMetadata: entry.productMetadata
        )
        foodStore.updateEntry(updated.removingPhotos(withIDs: removedPhotoIDs))
        dismiss()
    }
}
