import SwiftUI

struct ActiveFastingRow: View {
    let session: FastingSession

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let elapsed = session.duration(at: context.date)
            let progress = min(1, elapsed / TimeInterval(session.goalMinutes * 60))
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14)
                            .fill(.ultraThinMaterial)
                        Text("⏳")
                            .font(.system(size: 28))
                    }
                    .frame(width: 56, height: 56)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Fast in progress")
                            .font(.system(.body, design: .rounded, weight: .semibold))
                        Text("Started \(session.startedAt.formatted(date: .omitted, time: .shortened))")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(FastingDurationFormatter.compact(seconds: elapsed))
                            .font(.system(.headline, design: .rounded, weight: .bold))
                            .foregroundStyle(AppColors.calorie)
                            .monospacedDigit()
                        Text("\(FastingDurationFormatter.goal(minutes: session.goalMinutes)) goal")
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }

                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(AppColors.calorie.opacity(0.16))
                        Capsule()
                            .fill(AppColors.calorie)
                            .frame(width: proxy.size.width * progress)
                    }
                }
                .frame(height: 5)
            }
            .padding(.vertical, 4)
        }
    }
}

struct CompletedFastingRow: View {
    let session: FastingSession

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(.ultraThinMaterial)
                Text("⏳")
                    .font(.system(size: 28))
            }
            .frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text("\(FastingDurationFormatter.compact(seconds: session.duration())) fast")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                if let endedAt = session.endedAt {
                    Text("\(session.startedAt.formatted(date: .omitted, time: .shortened)) – \(endedAt.formatted(date: .omitted, time: .shortened))")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            Text("\(FastingDurationFormatter.goal(minutes: session.goalMinutes)) goal")
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
    }
}

struct FastingStartSheet: View {
    @Environment(\.dismiss) private var dismiss
    let defaultGoalMinutes: Int
    let onStart: (Int) -> Void
    @State private var selectedHours: Int

    init(defaultGoalMinutes: Int, onStart: @escaping (Int) -> Void) {
        self.defaultGoalMinutes = defaultGoalMinutes
        self.onStart = onStart
        _selectedHours = State(initialValue: min(168, max(1, defaultGoalMinutes / 60)))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 22) {
                Image(systemName: "timer")
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)

                Text("Choose a fasting goal")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                HStack(spacing: 4) {
                    Picker("Hours", selection: $selectedHours) {
                        ForEach(1...168, id: \.self) { hours in
                            Text("\(hours)").tag(hours)
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 150, height: 170)
                    .clipped()
                    Text("hours")
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                Button {
                    onStart(selectedHours * 60)
                    dismiss()
                } label: {
                    Label("Start Fast", systemImage: "play.fill")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                            in: RoundedRectangle(cornerRadius: 14)
                        )
                }
                .padding(.horizontal, 24)
                Spacer()
            }
            .padding(.top, 24)
            .background(AppColors.appBackground)
            .navigationTitle("Start Fast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct FastingGoalPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onSave: (Int) -> Void
    @State private var selectedHours: Int

    init(currentGoalMinutes: Int, onSave: @escaping (Int) -> Void) {
        self.onSave = onSave
        _selectedHours = State(initialValue: min(168, max(1, currentGoalMinutes / 60)))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Default Fasting Goal")
                    .font(.system(.title2, design: .rounded, weight: .bold))
                HStack(spacing: 4) {
                    Picker("Hours", selection: $selectedHours) {
                        ForEach(1...168, id: \.self) { hours in
                            Text("\(hours)").tag(hours)
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 150, height: 190)
                    .clipped()
                    Text("hours")
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Button {
                    onSave(selectedHours * 60)
                    dismiss()
                } label: {
                    Text("Save")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .padding(.horizontal, 24)
                Spacer()
            }
            .padding(.top, 24)
            .background(AppColors.appBackground)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct FastingSessionEditorView: View {
    @Environment(\.dismiss) private var dismiss
    let session: FastingSession
    let onSave: (FastingSession) -> Bool
    let onEndNow: (FastingSession) -> Bool
    let onDelete: (FastingSession) -> Void

    @State private var startedAt: Date
    @State private var endedAt: Date
    @State private var goalHours: Int
    @State private var showOverlapError = false

    init(
        session: FastingSession,
        onSave: @escaping (FastingSession) -> Bool,
        onEndNow: @escaping (FastingSession) -> Bool,
        onDelete: @escaping (FastingSession) -> Void
    ) {
        self.session = session
        self.onSave = onSave
        self.onEndNow = onEndNow
        self.onDelete = onDelete
        _startedAt = State(initialValue: session.startedAt)
        _endedAt = State(initialValue: session.endedAt ?? .now)
        _goalHours = State(initialValue: min(168, max(1, session.goalMinutes / 60)))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Fast") {
                    DatePicker("Started", selection: $startedAt)
                    if !session.isActive {
                        DatePicker("Ended", selection: $endedAt, in: startedAt...)
                    }
                    Stepper(value: $goalHours, in: 1...168) {
                        HStack {
                            Text("Goal")
                            Spacer()
                            Text("\(goalHours)")
                            Text("hours")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    if session.isActive {
                        Button {
                            var updated = session
                            updated.startedAt = startedAt
                            updated.goalMinutes = goalHours * 60
                            if onEndNow(updated) {
                                dismiss()
                            } else {
                                showOverlapError = true
                            }
                        } label: {
                            Label("End Fast Now", systemImage: "stop.fill")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    Button(role: .destructive) {
                        onDelete(session)
                        dismiss()
                    } label: {
                        Label(
                            session.isActive ? String(localized: "Cancel Fast") : String(localized: "Delete Fast"),
                            systemImage: "trash"
                        )
                            .frame(maxWidth: .infinity)
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle(
                session.isActive ? String(localized: "Active Fast") : String(localized: "Edit Fast")
            )
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var updated = session
                        updated.startedAt = startedAt
                        updated.endedAt = session.isActive ? nil : max(endedAt, startedAt)
                        updated.goalMinutes = goalHours * 60
                        if onSave(updated) {
                            dismiss()
                        } else {
                            showOverlapError = true
                        }
                    }
                }
            }
            .alert("Fast overlaps another session", isPresented: $showOverlapError) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("Adjust the start or end time so fasting sessions do not overlap.")
            }
        }
    }
}
