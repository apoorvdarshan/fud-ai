import SwiftUI

/// Compact link beside Weight History and Body Fat History on Progress.
struct WorkoutHistoryLink: View {
    let sessions: [StrengthWorkoutSession]
    let onTap: () -> Void

    private var burnRecordCount: Int {
        sessions.filter { $0.caloriesBurned != nil }.count
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "flame.fill")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Workout History")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text(ProgressHistoryCountText.localized(burnRecordCount))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .background(AppColors.appCard)
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75)
            }
            .compositingGroup()
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens workout calorie history")
    }
}

/// Full workout-calorie history, intentionally mirroring Weight and Body Fat History.
struct WorkoutHistoryView: View {
    let sessions: [StrengthWorkoutSession]
    let onDelete: (StrengthWorkoutSession) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: StrengthWorkoutSession?
    @State private var visibleSessions: [StrengthWorkoutSession] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleSessions) { session in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\((session.caloriesBurned ?? 0).formatted()) kcal")
                                .font(.system(.body, design: .rounded, weight: .medium))
                            Text(workoutHistoryFormatter.string(from: session.calendarDiaryDate))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDeletion = session
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Workout History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            visibleSessions = sessions
                .filter { $0.caloriesBurned != nil }
                .sorted {
                    if $0.stableDiaryDateKey == $1.stableDiaryDateKey {
                        return $0.completedAt > $1.completedAt
                    }
                    return $0.stableDiaryDateKey > $1.stableDiaryDateKey
                }
        }
        .alert("Delete Workout Entry", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let session = pendingDeletion {
                    visibleSessions.removeAll { $0.id == session.id }
                    onDelete(session)
                }
                pendingDeletion = nil
            }
        } message: {
            if let session = pendingDeletion {
                Text("Remove \(workoutHistoryFormatter.string(from: session.calendarDiaryDate))'s entry of \((session.caloriesBurned ?? 0).formatted()) kcal? This also deletes the matching sample from Apple Health. The dated workout plan stays in your diary.")
            }
        }
    }
}

private let workoutHistoryFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.dateStyle = .medium
    formatter.timeStyle = .none
    return formatter
}()

/// Read-only Apple Health / Apple Watch sessions shown in the workout diary.
struct ImportedHealthWorkoutDaySection: View {
    let workouts: [ImportedHealthWorkout]

    var body: some View {
        if !workouts.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: "applewatch")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                    Text("Apple Health")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    Spacer()
                    Text("Imported")
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Color.primary.opacity(0.06), in: Capsule())
                }

                ForEach(workouts) { workout in
                    ImportedHealthWorkoutRow(workout: workout)
                }
            }
            .padding(14)
            .background(AppColors.appCard)
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75)
            }
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}

struct ImportedHealthWorkoutRow: View {
    let workout: ImportedHealthWorkout

    private var timeRangeText: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return "\(formatter.string(from: workout.startedAt)) – \(formatter.string(from: workout.endedAt))"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(workout.activityTitle)
                    .font(.system(.body, design: .rounded, weight: .medium))
                Spacer()
                if let calories = workout.totalEnergyBurned, calories > 0 {
                    Text("\(calories.formatted()) kcal")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                }
            }
            Text("\(ImportedHealthWorkoutFormatting.durationText(seconds: workout.durationSeconds)) · \(timeRangeText)")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            if let sourceSummary = workout.sourceSummary {
                Text(sourceSummary)
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Compact link beside calculated workout burn history on Progress.
struct ImportedHealthWorkoutHistoryLink: View {
    let workouts: [ImportedHealthWorkout]
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "applewatch")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Apple Health Workouts")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text(ProgressHistoryCountText.localized(workouts.count))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .background(AppColors.appCard)
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(AppColors.calorie.opacity(0.09), lineWidth: 0.75)
            }
            .compositingGroup()
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens imported Apple Health workout history")
    }
}

/// Read-only history for Apple Watch / Health workouts imported into Fud AI.
struct ImportedHealthWorkoutHistoryView: View {
    let workouts: [ImportedHealthWorkout]

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(workouts) { workout in
                    VStack(alignment: .leading, spacing: 4) {
                        ImportedHealthWorkoutRow(workout: workout)
                        Text(workoutHistoryFormatter.string(from: workout.calendarDiaryDate))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Apple Health Workouts")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
