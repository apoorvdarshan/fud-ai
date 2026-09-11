import PhotosUI
import SwiftUI

struct UserExerciseEditorView: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(\.dismiss) private var dismiss

    let existingItemID: String?
    let onSaved: ((ExerciseLibraryItem) -> Void)?

    @State private var draft = UserExerciseDraft()
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var previewImage: UIImage?
    @State private var showDeleteConfirmation = false

    private var isEditing: Bool { existingItemID != nil }

    private var catalog: ExerciseLibraryService { ExerciseLibraryService.shared }

    init(existingItemID: String? = nil, onSaved: ((ExerciseLibraryItem) -> Void)? = nil) {
        self.existingItemID = existingItemID
        self.onSaved = onSaved
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Photo") {
                    photoSection
                }

                Section("Basics") {
                    TextField("Name", text: $draft.name)
                        .textInputAutocapitalization(.words)

                    Picker("Level", selection: $draft.rawLevel) {
                        ForEach(levelOptions, id: \.self) { Text($0).tag($0) }
                    }

                    Picker("Category", selection: $draft.category) {
                        ForEach(categoryOptions, id: \.self) { Text($0).tag($0) }
                    }
                }

                Section("Muscles & equipment") {
                    musclePicker(title: "Primary muscles", selection: $draft.primaryMuscles, options: catalog.availablePrimaryMuscles)
                    musclePicker(title: "Secondary muscles", selection: $draft.secondaryMuscles, options: catalog.availableSecondaryMuscles)

                    Picker("Equipment", selection: $draft.rawEquipment) {
                        ForEach(equipmentOptions, id: \.self) { Text($0).tag($0) }
                    }

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
                    Button("Save") { save() }
                        .disabled(draft.trimmedName.isEmpty)
                }
            }
            .onAppear(perform: loadExisting)
            .onChange(of: selectedPhotoItem) { _, item in
                Task { await loadPhoto(from: item) }
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
                    dismiss()
                }
            } message: {
                Text("This removes the exercise from your library. Logged workouts keep their history.")
            }
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

        if previewImage != nil {
            Button("Remove photo", role: .destructive) {
                previewImage = nil
                selectedPhotoItem = nil
                draft.photoData = nil
                draft.removePhoto = true
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
        if let filename = template.imagePaths.first,
           let data = FoodImageStore.shared.load(filename: filename),
           let image = UIImage(data: data) {
            previewImage = image
        }
    }

    private func loadPhoto(from item: PhotosPickerItem?) async {
        guard let item else { return }
        guard let data = try? await item.loadTransferable(type: Data.self),
              let image = UIImage(data: data) else { return }
        await MainActor.run {
            previewImage = image
            draft.photoData = data
            draft.removePhoto = false
        }
    }

    private func save() {
        guard let saved = workoutStore.saveUserExercise(draft, existingItemID: existingItemID) else { return }
        onSaved?(saved)
        dismiss()
    }
}
