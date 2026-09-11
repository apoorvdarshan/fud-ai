import SwiftUI

/// Workout preferences embedded directly in Fud AI's main Settings list.
struct WorkoutLoggingSettingsSection: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @AppStorage(OutdoorActivitySettings.enabledKey) private var walkRunQuickLogEnabled = false

    @State private var draft = StrengthWorkoutPreferences()
    @State private var hasLoaded = false

    var body: some View {
        Section {
            HStack {
                Label {
                    Text("Walk & Run")
                } icon: {
                    Image(systemName: "figure.walk")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Toggle("Walk & Run", isOn: $walkRunQuickLogEnabled)
                    .labelsHidden()
                    .tint(AppColors.calorie)
            }

            WorkoutSplitPickerRow(
                title: "Training Split",
                systemImage: "square.grid.2x2.fill",
                selection: $draft.split
            )

            WorkoutRPEScalePickerRow(
                title: "RPE Scale",
                systemImage: "gauge.with.dots.needle.50percent",
                selection: $draft.rpeScale
            )

            rpeScaleGuide
        } header: {
            Text("Workout")
        } footer: {
            Text("Off by default. When enabled, Walking and Running appear in the Workouts + menu for quick outdoor logging.")
        }
        .listRowBackground(AppColors.appCard)
        .onAppear(perform: loadPreferences)
        .onChange(of: draft) { _, newValue in
            guard hasLoaded else { return }
            workoutStore.updatePreferences { $0 = newValue }
        }
    }

    private func loadPreferences() {
        guard !hasLoaded else { return }
        draft = workoutStore.preferences
        hasLoaded = true
    }

    private var rpeScaleGuide: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("**Strength 1–10:** Lifting effort based on how many reps you had left.")
            Text("**CR10 0–10:** General effort from rest to maximum.")
            Text("**Borg 6–20:** Endurance effort linked to breathing and heart rate.")
        }
        .font(.system(.footnote, design: .rounded))
        .foregroundStyle(.secondary)
        .lineSpacing(1)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.leading, 32)
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "RPE scale guide. Strength 1 to 10 measures lifting effort by reps left. "
                + "CR10 0 to 10 measures general effort from rest to maximum. "
                + "Borg 6 to 20 measures endurance effort using breathing and heart rate."
        )
    }
}
