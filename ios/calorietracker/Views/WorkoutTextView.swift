import SwiftUI

struct WorkoutTextView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    let selectedDate: Date
    let unit: WeightUnit
    let bodyWeightKg: Double
    let onAdded: (Date) -> Void
    @State private var description = ""
    @State private var draft: WorkoutTextDraft?
    @State private var voice = false
    @State private var busy = false
    @State private var error: String?
    @State private var request: Task<Void, Never>?
    private var library: [ExerciseLibraryItem] { workoutStore.exerciseLibrary.exercises }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let binding = Binding($draft) {
                        WorkoutTextReview(draft: binding)
                        if let planned = try? binding.wrappedValue.planned(library: library),
                           let estimate = StrengthWorkoutBurnEstimator.estimate(exercises: planned, bodyWeightKg: bodyWeightKg,
                               defaultWeightUnit: unit, defaultRPEScale: workoutStore.preferences.rpeScale) {
                            Text("Estimated burn: \(estimate.calories) kcal").font(.headline)
                        }
                        Text("Calories are estimates. Use Calculate in the diary after adding your workout.")
                            .font(.footnote).foregroundStyle(.secondary)
                        HStack {
                            Button("Edit description") { draft = nil; error = nil }
                                .buttonStyle(.bordered)
                            Button("Add to diary", action: save).buttonStyle(.borderedProminent)
                                .disabled(binding.wrappedValue.exercises.isEmpty)
                        }
                    } else {
                        Text("Describe what you did. Review the details before adding them to your workout diary.")
                        TextField("20 minutes of rope skipping, then 3 sets of 10 bench presses at 40 kg",
                                  text: $description, axis: .vertical)
                            .lineLimit(4...10).textFieldStyle(.roundedBorder)
                            .accessibilityLabel("Workout description")
                            .onChange(of: description) { _, value in
                                if value.count > 4000 { description = String(value.prefix(4000)) }
                                error = nil
                            }
                        HStack {
                            Button("Voice", systemImage: "mic") { voice = true }.buttonStyle(.bordered)
                            Button(busy ? "Preparing…" : "Preview workout", action: analyze)
                                .buttonStyle(.borderedProminent)
                                .disabled(description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                        if busy { ProgressView() }
                    }
                    if let error { Text(error).foregroundStyle(.red) }
                }
                .padding(20)
                .disabled(busy)
            }
            .navigationTitle("Text / Voice workout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .sheet(isPresented: $voice) {
                VoiceInputView(onCancel: { voice = false }, onSubmit: { text in
                    description = String([description, text].filter { !$0.isEmpty }.joined(separator: "\n").prefix(4000))
                    error = nil
                    voice = false
                })
                .padding().presentationDetents([.large])
            }
            .onDisappear { request?.cancel() }
        }
    }

    private func analyze() {
        busy = true; error = nil
        request = Task { @MainActor in
            defer { busy = false }
            do {
                let result = try await GeminiService.analyzeWorkout(description: description, date: selectedDate,
                                                                     unit: unit, library: library)
                try Task.checkCancellation()
                draft = result
            } catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }

    private func save() {
        guard let draft else { return }
        do {
            try workoutStore.addTextWorkout(draft, library: library)
            if let date = StrengthWorkoutDate.date(for: draft.date) { onAdded(date) }
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

private struct WorkoutTextReview: View {
    @Binding var draft: WorkoutTextDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Review workout").font(.headline)
            TextField("Date (YYYY-MM-DD)", text: $draft.date).textFieldStyle(.roundedBorder)
                .accessibilityLabel("Date (YYYY-MM-DD)")
            ForEach($draft.exercises) { $exercise in
                VStack(alignment: .leading, spacing: 10) {
                    Text(exercise.name).font(.headline)
                    if exercise.exerciseID == nil {
                        Text("Custom timed activity · calorie estimate uses a general activity rate")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if !exercise.minutes.isEmpty || exercise.sets.isEmpty {
                        HStack {
                            Text("Minutes")
                            TextField("Minutes", text: $exercise.minutes).keyboardType(.decimalPad)
                                .textFieldStyle(.roundedBorder).accessibilityLabel("Minutes")
                        }
                    }
                    if !exercise.minutes.isEmpty {
                        Text("Effort (moderate if unspecified)").font(.caption)
                        Picker("Effort", selection: $exercise.intensity) {
                            Text("Light").tag("light")
                            Text("Moderate").tag("moderate")
                            Text("Vigorous").tag("vigorous")
                        }.pickerStyle(.segmented)
                    }
                    if !exercise.sets.isEmpty {
                        Picker("Weight unit", selection: $exercise.unit) {
                            Text("kg").tag("kg")
                            Text("lbs").tag("lbs")
                        }.pickerStyle(.segmented)
                        ForEach($exercise.sets) { $set in
                            HStack {
                                Text("Set \((exercise.sets.firstIndex { $0.id == set.id } ?? 0) + 1)")
                                    .font(.caption)
                                TextField("Weight (\(exercise.unit))", text: $set.weight)
                                    .keyboardType(.decimalPad).accessibilityLabel("Weight (\(exercise.unit))")
                                TextField("Reps", text: $set.reps).keyboardType(.numberPad).accessibilityLabel("Reps")
                            }.textFieldStyle(.roundedBorder)
                        }
                    }
                    Button("Remove exercise", role: .destructive) {
                        let id = exercise.id
                        draft.exercises.removeAll { $0.id == id }
                    }
                }
                .padding(14)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }
}
