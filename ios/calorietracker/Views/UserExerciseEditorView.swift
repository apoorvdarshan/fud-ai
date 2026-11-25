import PhotosUI
import SwiftUI

struct UserExerciseEditorView: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(\.dismiss) private var dismiss

    let existingItemID: String?
    let onSaved: ((ExerciseLibraryItem) -> Void)?
    let onDeleted: (() -> Void)?

    @State private var draft = UserExerciseDraft()
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var previewImage: UIImage?
    @State private var showDeleteConfirmation = false
    @State private var loadedItemID: String?
    @State private var isPhotoLoading = false
    @State private var photoLoadGeneration = 0
    @State private var isCameraPresented = false
    @State private var capturedImage: UIImage?
    @State private var isFillingWithAI = false
    @State private var aiFillError: String?
    @State private var aiFillRequestID: UUID?
    @State private var aiFillTask: Task<Void, Never>?

    private var isEditing: Bool { existingItemID != nil }

    private var catalog: ExerciseLibraryService { ExerciseLibraryService.shared }

    init(
        existingItemID: String? = nil,
        onSaved: ((ExerciseLibraryItem) -> Void)? = nil,
        onDeleted: (() -> Void)? = nil
    ) {
        self.existingItemID = existingItemID
        self.onSaved = onSaved
        self.onDeleted = onDeleted
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    photoSection
                } header: {
                    Text("Photo")
                } footer: {
                    if previewImage != nil {
                        aiFillFooter
                    }
                }

                // The fields "Fill with AI" writes are locked while it runs, so its
                // reply never overwrites an edit made in the meantime.
                Section("Basics") {
                    TextField("Name", text: $draft.name)
                        .textInputAutocapitalization(.words)
                        .disabled(isFillingWithAI)

                    Picker("Level", selection: $draft.rawLevel) {
                        ForEach(levelOptions, id: \.self) { Text($0).tag($0) }
                    }

                    Picker("Category", selection: $draft.category) {
                        ForEach(categoryOptions, id: \.self) { Text($0).tag($0) }
                    }
                    .disabled(isFillingWithAI)
                }

                Section("Muscles & equipment") {
                    musclePicker(title: "Primary muscles", selection: $draft.primaryMuscles, options: catalog.availablePrimaryMuscles)
                        .disabled(isFillingWithAI)
                    musclePicker(title: "Secondary muscles", selection: $draft.secondaryMuscles, options: catalog.availableSecondaryMuscles)
                        .disabled(isFillingWithAI)

                    Picker("Equipment", selection: $draft.rawEquipment) {
                        ForEach(equipmentOptions, id: \.self) { Text($0).tag($0) }
                    }
                    .disabled(isFillingWithAI)

                    Picker("Force", selection: $draft.force) {
                        ForEach(forceOptions, id: \.self) { Text($0).tag($0) }
                    }

                    Picker("Mechanic", selection: $draft.mechanic) {
                        ForEach(mechanicOptions, id: \.self) { Text($0).tag($0) }
                    }
                }

                Section("Instructions") {
                    TextEditor(text: $draft.instructions)
                        .frame(minHeight: 120)
                        .disabled(isFillingWithAI)
                }

                if isEditing {
                    Section {
                        Button("Delete exercise", role: .destructive) {
                            showDeleteConfirmation = true
                        }
                    }
                }
            }
            .navigationTitle(isEditing ? "Edit exercise" : "Create exercise")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    // Waits for a running AI fill, so its suggestions are never dropped.
                    Button("Save") { save() }
                        .disabled(draft.trimmedName.isEmpty || isPhotoLoading || isFillingWithAI)
                }
            }
            .task(id: existingItemID) {
                guard let existingItemID else { return }
                guard loadedItemID != existingItemID else { return }
                loadedItemID = existingItemID
                loadExisting()
            }
            .onChange(of: selectedPhotoItem) { _, item in
                Task { await loadPhoto(from: item) }
            }
            .fullScreenCover(isPresented: $isCameraPresented) {
                CameraView(image: $capturedImage)
                    .ignoresSafeArea()
            }
            .onChange(of: capturedImage) { _, image in
                guard let image else { return }
                capturedImage = nil
                useCapturedPhoto(image)
            }
            .confirmationDialog(
                "Delete this custom exercise?",
                isPresented: $showDeleteConfirmation,
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) {
                    if let existingItemID {
                        workoutStore.deleteUserExercise(itemID: existingItemID)
                    }
                    onDeleted?()
                    dismiss()
                }
            } message: {
                Text("This removes the exercise from your library. Logged workouts keep their history.")
            }
        }
        .onDisappear {
            cancelAIFill()
        }
    }

    @ViewBuilder
    private var photoSection: some View {
        ZStack {
            if let previewImage {
                Image(uiImage: previewImage)
                    .resizable()
                    .scaledToFill()
                    .frame(height: 180)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            } else {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.workoutPanel.opacity(0.35))
                    .frame(height: 180)
                    .overlay {
                        VStack(spacing: 8) {
                            Image(systemName: "figure.strengthtraining.traditional")
                                .font(.title2.weight(.semibold))
                            Text("Add photo (optional)")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.workoutMutedText)
                        }
                    }
            }
        }

        PhotosPicker(selection: $selectedPhotoItem, matching: .images) {
            Label(previewImage == nil ? "Choose photo" : "Replace photo", systemImage: "photo")
        }
        .disabled(isPhotoLoading || isFillingWithAI)

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            Button {
                isCameraPresented = true
            } label: {
                Label("Take photo", systemImage: "camera")
            }
            .disabled(isPhotoLoading || isFillingWithAI)
        }

        if previewImage != nil {
            Button {
                fillWithAI()
            } label: {
                HStack {
                    Label("Fill with AI", systemImage: "sparkles")
                    if isFillingWithAI {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(isPhotoLoading || isFillingWithAI)
        }

        if previewImage != nil {
            Button("Remove photo", role: .destructive) {
                cancelAIFill()
                photoLoadGeneration += 1
                previewImage = nil
                selectedPhotoItem = nil
                draft.photoData = nil
                draft.removePhoto = true
                isPhotoLoading = false
            }
            .disabled(isPhotoLoading || isFillingWithAI)
        }
    }

    private var aiFillFooter: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let aiFillError {
                Text(aiFillError)
                    .foregroundStyle(.red)
            }
            Text("Fill with AI sends this photo to your AI provider. Review the suggested details before saving.")
            if isFillingWithAI {
                WaitingFactCard()
                    .padding(.top, 4)
            }
        }
    }

    @ViewBuilder
    private func musclePicker(title: String, selection: Binding<[String]>, options: [String]) -> some View {
        NavigationLink(title) {
            List(options, id: \.self) { muscle in
                Button {
                    toggleMuscle(muscle, in: selection)
                } label: {
                    HStack {
                        Text(muscle)
                        Spacer()
                        if selection.wrappedValue.contains(muscle) {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.workoutAccent)
                        }
                    }
                }
                .foregroundStyle(Color.primary)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var levelOptions: [String] {
        ["Beginner", "Intermediate", "Expert"]
    }

    private var categoryOptions: [String] {
        let fromCatalog = catalog.availableCategoryCounts.map(\.category)
        return fromCatalog.isEmpty ? ["Strength", "Cardio", "Stretching"] : fromCatalog
    }

    private var equipmentOptions: [String] {
        ["Unspecified"] + catalog.availableRawEquipment.filter { $0 != "Unspecified" }
    }

    private var forceOptions: [String] {
        ["Unspecified"] + catalog.availableForces.filter { $0 != "Unspecified" }
    }

    private var mechanicOptions: [String] {
        ["Unspecified"] + catalog.availableMechanics.filter { $0 != "Unspecified" }
    }

    private var aiFillOptions: UserExerciseAIFill.Options {
        UserExerciseAIFill.Options(
            categories: categoryOptions,
            equipment: equipmentOptions.filter { $0 != "Unspecified" },
            primaryMuscles: catalog.availablePrimaryMuscles,
            secondaryMuscles: catalog.availableSecondaryMuscles
        )
    }

    private func toggleMuscle(_ muscle: String, in selection: Binding<[String]>) {
        if selection.wrappedValue.contains(muscle) {
            selection.wrappedValue.removeAll { $0 == muscle }
        } else {
            selection.wrappedValue.append(muscle)
        }
    }

    private func loadExisting() {
        guard let existingItemID,
              let template = workoutStore.userExerciseTemplate(for: existingItemID) else { return }
        draft = UserExerciseDraft(
            name: template.name,
            instructions: template.instructions.joined(separator: "\n"),
            rawLevel: template.rawLevel,
            force: template.force,
            mechanic: template.mechanic,
            category: template.category,
            rawEquipment: template.rawEquipment,
            primaryMuscles: template.primaryMuscles,
            secondaryMuscles: template.secondaryMuscles
        )
        cancelAIFill()
        let generation = photoLoadGeneration + 1
        photoLoadGeneration = generation
        isPhotoLoading = true
        if let filename = template.imagePaths.first,
           let data = FoodImageStore.shared.load(filename: filename),
           let image = UIImage(data: data) {
            previewImage = image
        } else {
            previewImage = nil
        }
        isPhotoLoading = false
    }

    private func loadPhoto(from item: PhotosPickerItem?) async {
        guard let item else { return }
        let generation = photoLoadGeneration + 1
        await MainActor.run {
            photoLoadGeneration = generation
            isPhotoLoading = true
        }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data) else {
            await MainActor.run {
                guard photoLoadGeneration == generation else { return }
                isPhotoLoading = false
            }
            return
        }
        await MainActor.run {
            guard photoLoadGeneration == generation else { return }
            cancelAIFill()
            previewImage = image
            draft.photoData = data
            draft.removePhoto = false
            isPhotoLoading = false
        }
    }

    private func useCapturedPhoto(_ image: UIImage) {
        guard draft.setCapturedPhoto(image) else { return }
        cancelAIFill()
        // Supersedes any library photo that is still loading.
        photoLoadGeneration += 1
        selectedPhotoItem = nil
        previewImage = image
        isPhotoLoading = false
    }

    /// Prefills the form from the photo. The user reviews the fields and saves as usual.
    private func fillWithAI() {
        guard let image = previewImage, !isFillingWithAI else { return }
        let requestID = UUID()
        let options = aiFillOptions
        aiFillRequestID = requestID
        aiFillError = nil
        isFillingWithAI = true
        aiFillTask = Task { @MainActor in
            do {
                let suggestion = try await GeminiService.suggestUserExercise(image: image, options: options)
                try Task.checkCancellation()
                guard aiFillRequestID == requestID else { return }
                draft.apply(suggestion)
            } catch is CancellationError {
                return
            } catch {
                guard aiFillRequestID == requestID, !Task.isCancelled else { return }
                aiFillError = GeminiService.analysisErrorMessage(error)
            }
            if aiFillRequestID == requestID {
                aiFillRequestID = nil
                isFillingWithAI = false
            }
        }
    }

    /// A new or removed photo makes a running fill and its error stale.
    private func cancelAIFill() {
        aiFillTask?.cancel()
        aiFillTask = nil
        aiFillRequestID = nil
        aiFillError = nil
        isFillingWithAI = false
    }

    private func save() {
        guard !isFillingWithAI, let saved = workoutStore.saveUserExercise(draft, existingItemID: existingItemID) else { return }
        onSaved?(saved)
        dismiss()
    }
}

extension UserExerciseDraft {
    /// Uses a camera capture as the exercise photo. Captures arrive at full sensor
    /// resolution, so they are stored as a downscaled JPEG; a pending "Remove photo"
    /// is cancelled. Returns false when the image cannot be encoded.
    @discardableResult
    mutating func setCapturedPhoto(_ image: UIImage) -> Bool {
        guard let data = try? GeminiService.encodedJPEGData(for: image) else { return false }
        photoData = data
        removePhoto = false
        return true
    }
}
