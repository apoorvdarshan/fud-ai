import SwiftUI
import PhotosUI

extension GeminiService.FoodAnalysis {
    /// One ingredient line for add-ingredient flows (nested ingredients stay collapsed).
    func asMealIngredient() -> MealIngredient {
        MealIngredient(
            name: name,
            grams: servingSizeGrams.isFinite && servingSizeGrams > 0 ? servingSizeGrams : 0,
            calories: calories,
            protein: protein,
            carbs: carbs,
            fat: fat,
            emoji: emoji
        )
    }
}

/// Plus menu that appends ingredients onto an open review/edit draft.
struct IngredientAddMenuButton: View {
    let onManual: () -> Void
    let onIngredient: (MealIngredient) -> Void

    @State private var showText = false
    @State private var showVoice = false
    @State private var showBarcode = false
    @State private var showCamera = false
    @State private var capturedImage: UIImage?
    @State private var showPhotoPicker = false
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var savedMode: SavedMealsMode?
    @State private var textDraft = ""
    @State private var isBusy = false
    @State private var errorMessage: String?

    var body: some View {
        Menu {
            Button { showCamera = true } label: {
                Label("Camera", systemImage: "camera.fill")
            }
            Button { showPhotoPicker = true } label: {
                Label("Photos", systemImage: "photo.on.rectangle")
            }
            Button { showBarcode = true } label: {
                Label("Barcode", systemImage: "barcode.viewfinder")
            }
            Button {
                textDraft = ""
                showText = true
            } label: {
                Label("Text", systemImage: "character.cursor.ibeam")
            }
            Button { showVoice = true } label: {
                Label("Voice", systemImage: "mic.fill")
            }
            Button(action: onManual) {
                Label("Manual", systemImage: "square.and.pencil")
            }
            Button { savedMode = .favorites } label: {
                Label("Favorites", systemImage: "heart.fill")
            }
            Button { savedMode = .frequent } label: {
                Label("Frequent", systemImage: "repeat")
            }
            Button { savedMode = .recent } label: {
                Label("Recent", systemImage: "clock.fill")
            }
        } label: {
            Label("Add Ingredient", systemImage: "plus.circle.fill")
                .font(.system(.body, design: .rounded, weight: .semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
        }
        .tint(AppColors.calorie)
        .disabled(isBusy)
        .overlay {
            if isBusy { ProgressView() }
        }
        .alert("Couldn't add ingredient", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .sheet(isPresented: $showText) {
            NavigationStack {
                Form {
                    TextField("Describe the food", text: $textDraft, axis: .vertical)
                        .lineLimit(3...6)
                }
                .navigationTitle("Add Ingredient")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showText = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Analyze") {
                            let text = textDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                            showText = false
                            runAnalysis {
                                try await GeminiService.analyzeTextInput(description: text, skipHostedMetering: true)
                            }
                        }
                        .disabled(textDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
            .presentationDetents([.medium])
        }
        .sheet(isPresented: $showVoice) {
            VoiceInputView(
                onCancel: { showVoice = false },
                onSubmit: { text in
                    showVoice = false
                    runAnalysis {
                        try await GeminiService.analyzeTextInput(description: text, skipHostedMetering: true)
                    }
                }
            )
        }
        .fullScreenCover(isPresented: $showBarcode) {
            BarcodeScannerView(
                onScan: { code in
                    showBarcode = false
                    runAnalysis {
                        try await OpenFoodFactsService.lookup(barcode: code)
                    }
                },
                onCancel: { showBarcode = false }
            )
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraView(
                image: $capturedImage,
                onCancel: { showCamera = false }
            )
            .ignoresSafeArea()
        }
        .onChange(of: capturedImage) { _, image in
            guard let image else { return }
            capturedImage = nil
            showCamera = false
            runAnalysis(image: image) {
                try await GeminiService.analyzeFood(images: [image], skipHostedMetering: true)
            }
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItems, maxSelectionCount: 1, matching: .images)
        .onChange(of: selectedPhotoItems) { _, items in
            guard let item = items.first else { return }
            selectedPhotoItems = []
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let image = UIImage(data: data) else {
                    errorMessage = "Couldn't load that photo."
                    return
                }
                runAnalysis(image: image) {
                    try await GeminiService.analyzeFood(images: [image], skipHostedMetering: true)
                }
            }
        }
        .sheet(item: $savedMode) { mode in
            RecentsView(mode: mode, logDate: Date()) { entry in
                onIngredient(entry.asMealIngredient())
            }
        }
    }

    @MainActor
    private func runHostedIngredientAnalysis(
        _ work: @escaping () async throws -> GeminiService.FoodAnalysis
    ) async throws -> GeminiService.FoodAnalysis {
        try await AIGate.runWithHostedQuota(.ingredientAI) {
            try await work()
        }
    }

    private func runAnalysis(image: UIImage? = nil, _ work: @escaping () async throws -> GeminiService.FoodAnalysis) {
        guard !isBusy else { return }
        isBusy = true
        errorMessage = nil
        Task {
            do {
                let analysis = try await runHostedIngredientAnalysis(work)
                await MainActor.run {
                    isBusy = false
                    var ingredient = analysis.asMealIngredient()
                    if let data = image?.jpegData(compressionQuality: 0.8) {
                        ingredient.imageFilename = FoodImageStore.shared.store(data: data, for: ingredient.id)
                    }
                    onIngredient(ingredient)
                }
            } catch {
                await MainActor.run {
                    isBusy = false
                    errorMessage = error.localizedDescription
                }
            }
        }
    }
}
