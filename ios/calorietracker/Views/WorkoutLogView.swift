import SwiftUI
import UIKit

/// Ephemeral logger state owned by the Workouts tab so switching between the
/// library and logger does not discard the selected day.
/// Planned and completed workouts remain persisted by `StrengthWorkoutStore`.
@Observable
final class WorkoutLogSessionState {
    var selectedDate = Date.now

    func reset() {
        selectedDate = .now
    }

    /// Moves the diary by whole days while keeping forward swipes bounded by today,
    /// matching the nutrition diary's navigation behavior.
    @discardableResult
    func moveSelectedDay(
        by delta: Int,
        now: Date = .now,
        calendar: Calendar = .current
    ) -> Bool {
        guard delta != 0,
              let newDate = calendar.date(byAdding: .day, value: delta, to: selectedDate)
        else { return false }

        if delta > 0 && calendar.startOfDay(for: newDate) > calendar.startOfDay(for: now) {
            return false
        }

        selectedDate = newDate
        return true
    }
}

enum WorkoutLogDaySwipeNavigation {
    static func dayDelta(for translation: CGSize) -> Int? {
        let dx = translation.width
        let dy = translation.height
        guard abs(dx) > 60, abs(dx) > abs(dy) * 1.5 else { return nil }
        return dx < 0 ? 1 : -1
    }
}

/// Reorder mode belongs to the day it was started on. It ends when another
/// day is selected or fewer than two exercises remain, so returning to that
/// day (or adding an exercise back) shows the normal cards instead of
/// silently resuming it.
enum WorkoutLogReorderMode {
    static func retainedDateKey(_ dateKey: String?, selectedDateKey: String, exerciseCount: Int) -> String? {
        guard let dateKey, dateKey == selectedDateKey, exerciseCount > 1 else { return nil }
        return dateKey
    }
}

enum WorkoutLogKeyboardDismissal {
    static func shouldDismiss(at location: CGPoint, cardFrames: [CGRect]) -> Bool {
        guard !cardFrames.isEmpty else { return false }
        return !cardFrames.contains { $0.contains(location) }
    }
}

private enum WorkoutLogLayout {
    static let coordinateSpace = "fudai.workout-log.list"
}

private struct WorkoutLogCardFramePreferenceKey: PreferenceKey {
    static let defaultValue: [UUID: CGRect] = [:]

    static func reduce(value: inout [UUID: CGRect], nextValue: () -> [UUID: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, latest in latest })
    }
}

/// The optional strength diary. Its information stays in `StrengthWorkoutStore`,
/// separate from the food diary, while the visual language is bridged through
/// Fud AI's existing workout theme tokens.
struct WorkoutLogView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedHealthWorkoutStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(ProfileStore.self) private var profileStore
    @AppStorage(WeightUnit.storageKey) private var weightUnitRaw = WeightUnit.lbs.rawValue
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @AppStorage(OutdoorActivitySettings.enabledKey) private var walkRunQuickLogEnabled = false
    @AppStorage(WorkoutDockShortcuts.storageKey) private var dockShortcutsRaw = ""

    @State private var pickerRequest: WorkoutLogPickerRequest?
    @State private var outdoorActivitySheet: OutdoorActivityDurationSheet.ActivityKind?
    @State private var isCreateExercisePresented = false
    @State private var isCopySheetPresented = false
    @State private var isTextSheetPresented = false
    @State private var workoutInputUsesVoice = false
    @State private var visibleDockShortcutCount = 3
    @State private var restTimer = WorkoutRestTimerController.shared
    @State private var isRoutinesSheetPresented = false
    @State private var isSaveRoutinePresented = false
    @State private var routineName = ""
    @State private var selectedDetailItem: ExerciseLibraryItem?
    @State private var historyRequest: WorkoutLogExerciseHistoryRequest?

    @State private var isNoPerformedSetAlertPresented = false
    @State private var isCalculatingBurn = false
    @State private var workoutCardFrames: [UUID: CGRect] = [:]
    @State private var pendingWorkoutDeletion: StrengthPlannedExercise?
    /// The day reordering was started on; cleared when the day changes or
    /// fewer than two exercises remain (see `WorkoutLogReorderMode`).
    @State private var reorderingDateKey: String?
    @FocusState private var focusedSetField: WorkoutLogSetFocus?

    private var isWorkoutDeletionPresented: Binding<Bool> {
        Binding(
            get: { pendingWorkoutDeletion != nil },
            set: { presented in
                if !presented { pendingWorkoutDeletion = nil }
            }
        )
    }

    private var library: ExerciseLibraryService { workoutStore.exerciseLibrary }
    private let session: WorkoutLogSessionState
    private let embedsInNavigationStack: Bool
    private let onShowLibrary: (() -> Void)?

    init(
        session: WorkoutLogSessionState = WorkoutLogSessionState(),
        embedsInNavigationStack: Bool = true,
        onShowLibrary: (() -> Void)? = nil
    ) {
        self.session = session
        self.embedsInNavigationStack = embedsInNavigationStack
        self.onShowLibrary = onShowLibrary
    }

    private var selectedDate: Date {
        get { session.selectedDate }
        nonmutating set { session.selectedDate = newValue }
    }

    private var selectedDateBinding: Binding<Date> {
        Binding(
            get: { session.selectedDate },
            set: { session.selectedDate = $0 }
        )
    }

    private var selectedExercises: [StrengthPlannedExercise] {
        workoutStore.exercises(for: selectedDate)
    }

    private var selectedDateKey: String {
        StrengthWorkoutStore.dateKey(for: selectedDate)
    }

    /// Rest runs only on today's log, and only while the rest timer is on.
    private var isRestAvailable: Bool {
        workoutStore.preferences.restSeconds > 0 && Calendar.current.isDateInToday(selectedDate)
    }

    private var isReordering: Bool {
        WorkoutLogReorderMode.retainedDateKey(
            reorderingDateKey,
            selectedDateKey: selectedDateKey,
            exerciseCount: selectedExercises.count
        ) != nil
    }

    /// Clears the stored day once reordering no longer applies, so it does not
    /// come back when the user returns to that day or adds an exercise.
    private func endReorderingIfNeeded() {
        reorderingDateKey = WorkoutLogReorderMode.retainedDateKey(
            reorderingDateKey,
            selectedDateKey: selectedDateKey,
            exerciseCount: selectedExercises.count
        )
    }

    private var importedHealthWorkouts: [ImportedHealthWorkout] {
        importedHealthWorkoutStore.workouts(on: selectedDate)
    }

    private var weightUnit: WeightUnit {
        WeightUnit(rawValue: weightUnitRaw) ?? .lbs
    }

    private var completedSetCount: Int {
        // Match Delts: a set counts once reps are entered. Weight/RPE alone is
        // still a planned, incomplete set.
        selectedExercises.flatMap(\.sets).filter { !$0.reps.isEmpty }.count
    }

    private var completedRepCount: Int {
        selectedExercises.flatMap(\.sets).reduce(0) { $0 + (Int($1.reps) ?? 0) }
    }

    private var currentBodyWeightKg: Double {
        weightStore.latestEntry?.weightKg ?? profileStore.profile.weightKg
    }

    private var splitGroups: [StrengthWorkoutSplitGroup] {
        StrengthWorkoutSplitGroup.selectionGroups(
            for: workoutStore.preferences.split,
            availablePrimaryMuscles: library.availablePrimaryMuscles,
            availableSecondaryMuscles: library.availableSecondaryMuscles
        )
    }

    private var copyableDays: [WorkoutLogCopyDay] {
        workoutStore.previousPlanDates(before: selectedDate).map { date in
            WorkoutLogCopyDay(date: date, exercises: workoutStore.exercises(for: date))
        }
    }

    /// Match Home's nutrition diary: respond only to a deliberate horizontal flick.
    /// This is attached to summary/empty-state surfaces rather than exercise cards,
    /// preserving their native Save and Delete swipe actions.
    private var daySwipeGesture: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                guard let delta = WorkoutLogDaySwipeNavigation.dayDelta(for: value.translation) else { return }
                changeDay(by: delta)
            }
    }

    var body: some View {
        Group {
            if embedsInNavigationStack {
                NavigationStack {
                    workoutContent
                }
            } else {
                workoutContent
            }
        }
        .workoutScreen()
        // Observe theme changes without re-keying the selected diary day.
        .animation(.easeInOut(duration: 0.2), value: appThemeColorRaw)
        .alert(
            "Delete Workout Log?",
            isPresented: isWorkoutDeletionPresented,
            presenting: pendingWorkoutDeletion
        ) { exercise in
            Button("Cancel", role: .cancel) { pendingWorkoutDeletion = nil }
            Button("Delete", role: .destructive) {
                confirmWorkoutDeletion(exercise)
            }
        } message: { _ in
            Text("This removes the workout from your diary. Saved exercises are kept.")
        }
    }

    private var workoutContent: some View {
        ScrollViewReader { proxy in
                List {
                    Section {
                        WorkoutLogWeekStrip(
                            selectedDate: selectedDateBinding,
                            workoutCountForDate: workoutStore.workoutCount
                        )
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    }

                    Section {
                        WorkoutLogBurnHero(
                            workoutCount: selectedExercises.count,
                            setCount: completedSetCount,
                            repCount: completedRepCount,
                            caloriesBurned: workoutStore.caloriesBurned(on: selectedDate),
                            isCalculatingBurn: isCalculatingBurn,
                            calculateBurn: calculateBurn,
                            changeDay: { changeDay(by: $0) }
                        )
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                    }

                    if !importedHealthWorkouts.isEmpty {
                        Section {
                            ImportedHealthWorkoutDaySection(workouts: importedHealthWorkouts)
                                .contentShape(Rectangle())
                                .simultaneousGesture(daySwipeGesture)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 0, leading: 16, bottom: 0, trailing: 16))
                        }
                    }

                    Section {
                        if selectedExercises.isEmpty {
                            WorkoutLogEmptyRoutineRow(splitTitle: workoutStore.preferences.split.title)
                                .contentShape(Rectangle())
                                .simultaneousGesture(daySwipeGesture)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 7, leading: 16, bottom: 7, trailing: 16))
                        } else if isReordering {
                            let count = selectedExercises.count
                            ForEach(Array(selectedExercises.enumerated()), id: \.element.id) { index, exercise in
                                WorkoutLogReorderRow(
                                    exercise: exercise,
                                    position: index + 1,
                                    canMoveUp: index > 0,
                                    canMoveDown: index < count - 1,
                                    moveUp: { moveExercise(exercise, by: -1) },
                                    moveDown: { moveExercise(exercise, by: 1) }
                                )
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 5, leading: 16, bottom: 5, trailing: 16))
                            }
                            // Long-press dragging is offered only here, so it never
                            // competes with set fields or swipe actions on normal cards.
                            .onMove { source, destination in
                                withAnimation(.snappy(duration: 0.25)) {
                                    workoutStore.moveExercises(fromOffsets: source, toOffset: destination, on: selectedDate)
                                }
                            }
                        } else {
                            let count = selectedExercises.count
                            ForEach(Array(selectedExercises.enumerated()), id: \.element.id) { index, exercise in
                                WorkoutLogExerciseCard(
                                    exercise: exercise,
                                    weightUnit: weightUnit,
                                    rpeScale: workoutStore.preferences.rpeScale,
                                    isSaved: workoutStore.savedExerciseIDs.contains(exercise.itemID),
                                    lastTimeSummary: workoutStore.lastExerciseLiftSummary(
                                        itemID: exercise.itemID,
                                        name: exercise.name,
                                        before: selectedDate,
                                        displayUnit: weightUnit
                                    ),
                                    focusedField: $focusedSetField,
                                    moveUp: index > 0 ? { moveExercise(exercise, by: -1) } : nil,
                                    moveDown: index < count - 1 ? { moveExercise(exercise, by: 1) } : nil,
                                    openDetail: {
                                        guard focusedSetField == nil else { return }
                                        selectedDetailItem = exercise.libraryItem
                                    },
                                    showHistory: {
                                        historyRequest = WorkoutLogExerciseHistoryRequest(
                                            itemID: exercise.itemID,
                                            name: exercise.name
                                        )
                                    },
                                    toggleSaved: {
                                        workoutStore.toggleSaved(exercise.itemID)
                                    },
                                    removeExercise: {
                                        requestWorkoutDeletion(exercise)
                                    },
                                    timerAction: { action in
                                        dismissSetKeyboard()
                                        workoutStore.updateTimer(action, exerciseID: exercise.id, on: selectedDate)
                                    },
                                    setEffort: { intensity in
                                        workoutStore.setTimerIntensity(intensity, exerciseID: exercise.id, on: selectedDate)
                                    },
                                    updateSetCount: { count in
                                        changeSetCount(count, exercise: exercise)
                                    },
                                    updateWeight: { setID, value in
                                        workoutStore.updateSet(
                                            exerciseID: exercise.id,
                                            setID: setID,
                                            on: selectedDate,
                                            weight: value,
                                            weightUnit: weightUnit
                                        )
                                    },
                                    updateReps: { setID, value in
                                        logReps(value, setID: setID, exercise: exercise)
                                    },
                                    updateRPE: { setID, value in
                                        workoutStore.updateSet(
                                            exerciseID: exercise.id,
                                            setID: setID,
                                            on: selectedDate,
                                            rpe: value
                                        )
                                    },
                                    restingSetID: restTimer.timer?.exerciseID == exercise.id ? restTimer.timer?.setID : nil,
                                    startRest: isRestAvailable ? { setID in
                                        startRest(after: setID, in: exercise)
                                    } : nil
                                )
                                .background {
                                    GeometryReader { geometry in
                                        Color.clear
                                            .allowsHitTesting(false)
                                            .preference(
                                                key: WorkoutLogCardFramePreferenceKey.self,
                                                value: [
                                                    exercise.id: geometry.frame(
                                                        in: .named(WorkoutLogLayout.coordinateSpace)
                                                    )
                                                ]
                                            )
                                    }
                                }
                                .id(exercise.id)
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                                .listRowInsets(EdgeInsets(top: 7, leading: 16, bottom: 7, trailing: 16))
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button {
                                        requestWorkoutDeletion(exercise)
                                    } label: {
                                        Label("Delete", systemImage: "trash.fill")
                                    }
                                    .tint(Color(red: 0.58, green: 0.10, blue: 0.08))

                                    Button {
                                        workoutStore.toggleSaved(exercise.itemID)
                                    } label: {
                                        let isSaved = workoutStore.savedExerciseIDs.contains(exercise.itemID)
                                        Label(
                                            isSaved ? "Unsave" : "Save",
                                            systemImage: isSaved ? "bookmark.slash.fill" : "bookmark.fill"
                                        )
                                    }
                                    .tint(Color(red: 0.18, green: 0.42, blue: 0.16))
                                }
                            }
                        }
                    } header: {
                        VStack(alignment: .leading, spacing: 4) {
                            Label(selectedDateTitle, systemImage: "dumbbell.fill")
                                .font(.system(.headline, weight: .black).width(.condensed))
                                .foregroundStyle(Color.workoutCharcoal)
                                .accessibilityAddTraits(.isHeader)
                            if selectedExercises.count > 1 {
                                ViewThatFits(in: .horizontal) {
                                    HStack {
                                        workoutCountLabel.fixedSize()
                                        Spacer(minLength: 8)
                                        reorderButton.fixedSize()
                                    }
                                    VStack(alignment: .leading, spacing: 4) {
                                        workoutCountLabel
                                        reorderButton
                                    }
                                }
                            } else {
                                workoutCountLabel
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 4)
                        .textCase(nil)
                        .contentShape(Rectangle())
                        .simultaneousGesture(daySwipeGesture)
                    }
                }
                .coordinateSpace(name: WorkoutLogLayout.coordinateSpace)
                .scrollContentBackground(.hidden)
                .background(Color.workoutBackground.ignoresSafeArea())
                .listSectionSpacing(8)
                .scrollDismissesKeyboard(.interactively)
                // Pinned at the top so it stays visible above the keyboard
                // while the next set is being typed.
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let timer = restTimer.timer {
                        WorkoutRestTimerBar(
                            timer: timer,
                            onExtend: { restTimer.extend() },
                            onSkip: { restTimer.skip() }
                        )
                        .padding(.horizontal, 16)
                        .padding(.top, 4)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }
                .animation(.snappy(duration: 0.25), value: restTimer.timer == nil)
                .animation(.snappy, value: selectedDate)
                .onPreferenceChange(WorkoutLogCardFramePreferenceKey.self) { frames in
                    workoutCardFrames = frames
                }
                .simultaneousGesture(
                    SpatialTapGesture(
                        coordinateSpace: .named(WorkoutLogLayout.coordinateSpace)
                    )
                        .onEnded { value in
                            guard WorkoutLogKeyboardDismissal.shouldDismiss(
                                at: value.location,
                                cardFrames: Array(workoutCardFrames.values)
                            ) else { return }
                            dismissSetKeyboard()
                        }
                )
                .onChange(of: selectedDateKey) { _, _ in endReorderingIfNeeded() }
                .onChange(of: selectedExercises.count) { _, _ in endReorderingIfNeeded() }
                .onChange(of: focusedSetField) { oldValue, newValue in
                    guard let setID = newValue?.setID,
                          setID != oldValue?.setID
                    else { return }

                    revealFocusedSet(setID, in: proxy)
                }
                .onChange(of: restTimer.timer == nil) { _, _ in
                    // Filling the first reps starts rest and inserts a pinned bar.
                    // Reposition the edit row in the newly shortened viewport.
                    if let id = focusedSetField?.setID { revealFocusedSet(id, in: proxy) }
                }
                .safeAreaInset(edge: .bottom, spacing: 0) {
                    if focusedSetField == nil {
                        workoutDock
                    } else {
                        // Reserve space in the list viewport instead of relying
                        // on a keyboard accessory that can overlay the last set.
                        HStack {
                            Spacer()
                            Button("Done", action: dismissSetKeyboard)
                                .padding(.horizontal, 12)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("workouts.keyboard.done")
                        }
                        .padding(.horizontal, 10)
                        .background(Color.workoutBackground)
                    }
                }
            }
            // Keep the chrome quiet: the date strip and burn calculator lead.
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(item: $selectedDetailItem) { item in
                ExerciseLibraryDetailView(item: item)
            }
            .alert("Log a workout first", isPresented: $isNoPerformedSetAlertPresented) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Stop and save an exercise timer, or enter reps for a strength exercise on \(selectedDateTitle), before calculating workout calories.")
            }
            .flashSheet(item: $pickerRequest) { request in
                WorkoutLogExercisePickerSheet(
                    request: request,
                    selectedDate: selectedDate,
                    onDone: { pickerRequest = nil }
                )
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
            }
            .flashSheet(isPresented: $isCreateExercisePresented) {
                UserExerciseEditorView()
            }
            .flashSheet(isPresented: $isRoutinesSheetPresented) {
                WorkoutRoutinesSheet(
                    onApply: { routine in
                        workoutStore.applyRoutine(routine.id, to: selectedDate)
                        isRoutinesSheetPresented = false
                    },
                    onClose: { isRoutinesSheetPresented = false }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .alert("Save Routine", isPresented: $isSaveRoutinePresented) {
                TextField("Routine name", text: $routineName)
                Button("Cancel", role: .cancel) {}
                Button("Save") {
                    workoutStore.saveRoutine(named: routineName, from: selectedDate)
                }
                .disabled(StrengthWorkoutRoutinePlanner.sanitizedName(routineName).isEmpty)
            } message: {
                Text("Saves this day's exercises with their planned sets, reps and weights.")
            }
            .flashSheet(isPresented: $isCopySheetPresented) {
                WorkoutLogCopySheet(
                    days: copyableDays,
                    targetTitle: selectedDateTitle,
                    onCopy: { sourceDate, includeSetDetails in
                        workoutStore.copyPlan(from: sourceDate, to: selectedDate, includeSetDetails: includeSetDetails)
                        isCopySheetPresented = false
                    },
                    onClose: { isCopySheetPresented = false }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .flashSheet(item: $historyRequest) { request in
                WorkoutLogExerciseHistorySheet(
                    request: request,
                    selectedDate: selectedDate,
                    weightUnit: weightUnit,
                    history: workoutStore.exerciseLiftHistory(
                        itemID: request.itemID,
                        name: request.name,
                        before: selectedDate
                    ),
                    onClose: { historyRequest = nil }
                )
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .flashSheet(item: $outdoorActivitySheet) { activity in
                OutdoorActivityDurationSheet(activity: activity) { minutes, intensity in
                    logQuickOutdoorActivity(activity, minutes: minutes, intensity: intensity)
                }
            }
        }

    private var workoutDock: some View {
        let shortcuts = WorkoutDockShortcuts(stored: dockShortcutsRaw).actions
        let count = min(visibleDockShortcutCount, shortcuts.count)
        let side = WorkoutDockShortcuts.side
        let gap = WorkoutDockShortcuts.gap
        let addSide = WorkoutDockShortcuts.addSide
        let slotCount = shortcuts.count
        return HStack(spacing: gap) {
            if let onShowLibrary {
                Button(action: onShowLibrary) {
                    Image(systemName: "dumbbell.fill")
                        .frame(width: side, height: side)
                }
                .buttonStyle(.bordered)
                .accessibilityLabel("Exercise library")
                .accessibilityIdentifier("nav.exerciseLibrary")
            }
            ForEach(0..<count, id: \.self) { slot in
                let action = shortcuts[slot]
                Button { openWorkoutShortcut(action) } label: {
                    Image(systemName: action.icon)
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(Color.workoutCharcoal)
                        .frame(width: side, height: side)
                        .flashPanel()
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(action.title))
                .accessibilityIdentifier("workouts.shortcut.\(slot)")
            }
            addExerciseMenu(overflow: Set(shortcuts.dropFirst(count)))
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .onGeometryChange(for: Int.self) { geometry in
            let switcherWidth = onShowLibrary == nil ? 0 : side + gap + 24
            return max(0, min(slotCount, Int((geometry.size.width - addSide - switcherWidth) / (side + gap))))
        } action: { visibleDockShortcutCount = $0 }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.workoutBackground)
        .simultaneousGesture(TapGesture().onEnded(dismissSetKeyboard))
    }

    private func openWorkoutShortcut(_ action: WorkoutDockAction) {
        dismissSetKeyboard()
        switch action {
        case .voice, .text:
            workoutInputUsesVoice = action == .voice
            isTextSheetPresented = true
        case .saved: pickerRequest = WorkoutLogPickerRequest(context: .saved, initialSource: .saved)
        case .walking: outdoorActivitySheet = .walking
        case .running: outdoorActivitySheet = .running
        case .routine: isRoutinesSheetPresented = true
        case .copy: isCopySheetPresented = true
        }
    }

    private func workoutMenuItem(_ action: WorkoutDockAction) -> FlashMenuItem {
        FlashMenuItem(Text(action.title), icon: action.icon) { openWorkoutShortcut(action) }
    }

    private func addExerciseMenu(overflow: Set<WorkoutDockAction>) -> some View {
        FlashMenu(title: Text("Add workout"), items: {
            var items = [
                workoutMenuItem(.text),
                workoutMenuItem(.voice),
                workoutMenuItem(.saved),
                FlashMenuItem(Text("Create exercise"), icon: "plus.circle") {
                    isCreateExercisePresented = true
                },
                workoutMenuItem(.copy),
                workoutMenuItem(.routine),
            ]
            if !selectedExercises.isEmpty {
                items.append(FlashMenuItem(Text("Save as routine"), icon: "square.and.arrow.down") {
                    routineName = ""
                    isSaveRoutinePresented = true
                })
            }
            for action in [WorkoutDockAction.walking, .running] where walkRunQuickLogEnabled || overflow.contains(action) {
                items.append(workoutMenuItem(action))
            }
            let contexts = splitGroups.isEmpty ? [.all] : splitGroups.map {
                WorkoutLogPickerContext(title: $0.title, muscles: $0.muscles)
            }
            items += contexts.map { context in
                FlashMenuItem(Text(context.title), iconAsset: MuscleGlyphAsset.name(title: context.title, muscles: context.muscles)) {
                    pickerRequest = WorkoutLogPickerRequest(context: context, initialSource: .dataset)
                }
            }
            return items
        }) {
            Image(systemName: "plus")
                .font(.system(size: 26, weight: .semibold))
                .frame(width: WorkoutDockShortcuts.addSide, height: WorkoutDockShortcuts.addSide)
                .flashActionFill()
        }
        .tint(Color.workoutAccent)
        .accessibilityLabel("Add workout")
        .flashPopover(isPresented: $isTextSheetPresented) {
            WorkoutTextView(selectedDate: selectedDate, unit: weightUnit, bodyWeightKg: currentBodyWeightKg,
                onAdded: { selectedDate = $0 }, startsWithVoice: workoutInputUsesVoice)
                .presentationCompactAdaptation(.popover)
        }
    }

    /// A labeled header button rather than a hidden long-press drag keeps reordering discoverable.
    private var reorderButton: some View {
        Button {
            dismissSetKeyboard()
            withAnimation(.snappy(duration: 0.25)) {
                reorderingDateKey = isReordering ? nil : selectedDateKey
            }
        } label: {
            Label(isReordering ? "Done" : "Reorder", systemImage: isReordering ? "checkmark" : "arrow.up.arrow.down")
                .font(.system(.subheadline, weight: .bold).width(.condensed))
                .foregroundStyle(isReordering ? Color.workoutOnAccent : Color.workoutCharcoal)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .frame(minHeight: 44)
                .background(isReordering ? Color.workoutAccent : AppColors.appCard)
                .pinkNeoRule(AppColors.flashRule)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("workout.log.reorder")
    }

    private var workoutCountLabel: some View {
        Text(workoutCountText(selectedExercises.count))
            .font(.system(.subheadline, weight: .semibold).width(.condensed))
            .foregroundStyle(Color.workoutMutedText)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func moveExercise(_ exercise: StrengthPlannedExercise, by offset: Int) {
        withAnimation(.snappy(duration: 0.25)) {
            workoutStore.moveExercise(exercise.id, by: offset, on: selectedDate)
        }
        AppHaptics.impact(.light)
    }

    private var selectedDateTitle: String {
        if Calendar.current.isDateInToday(selectedDate) { return String(localized: "Today") }
        if Calendar.current.isDateInTomorrow(selectedDate) { return String(localized: "Tomorrow") }
        if Calendar.current.isDateInYesterday(selectedDate) { return String(localized: "Yesterday") }
        return selectedDate.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }

    private func workoutCountText(_ count: Int) -> String {
        String(localized: "\(count) workouts")
    }

    /// Logging a set (its reps first filled in) on today's log starts the rest
    /// countdown; clearing a logged set's reps and typing them again does not.
    private func logReps(_ value: String, setID: UUID, exercise: StrengthPlannedExercise) {
        let previousReps = currentReps(setID: setID, exerciseID: exercise.id)
        workoutStore.updateSet(exerciseID: exercise.id, setID: setID, on: selectedDate, reps: value)
        guard WorkoutRestTimer.shouldStart(
            previousReps: previousReps,
            newReps: currentReps(setID: setID, exerciseID: exercise.id),
            restSeconds: workoutStore.preferences.restSeconds,
            isToday: Calendar.current.isDateInToday(selectedDate),
            alreadyRested: restTimer.hasRested(setID: setID)
        ) else { return }
        startRest(after: setID, in: exercise)
    }

    private func currentReps(setID: UUID, exerciseID: UUID) -> String {
        selectedExercises.first { $0.id == exerciseID }?.sets.first { $0.id == setID }?.reps ?? ""
    }

    /// On today's log, adding a set with the stepper finishes the previous set
    /// and starts its rest.
    private func changeSetCount(_ count: Int, exercise: StrengthPlannedExercise) {
        let before = currentSets(exerciseID: exercise.id)
        workoutStore.setSetCount(count, exerciseID: exercise.id, on: selectedDate)
        guard let setID = WorkoutRestTimer.setFinishedByAddingSets(
            before: before,
            after: currentSets(exerciseID: exercise.id),
            restSeconds: workoutStore.preferences.restSeconds,
            isToday: Calendar.current.isDateInToday(selectedDate),
            running: restTimer.timer
        ) else { return }
        startRest(after: setID, in: exercise)
    }

    private func currentSets(exerciseID: UUID) -> [StrengthPlannedSet] {
        selectedExercises.first { $0.id == exerciseID }?.sets ?? []
    }

    private func startRest(after setID: UUID, in exercise: StrengthPlannedExercise) {
        restTimer.start(
            exerciseID: exercise.id,
            setID: setID,
            exerciseName: exercise.name,
            durationSeconds: workoutStore.preferences.restSeconds
        )
    }

    private func changeDay(by delta: Int) {
        guard focusedSetField == nil,
              session.moveSelectedDay(by: delta)
        else { return }
        AppHaptics.impact(.light)
    }

    private func logQuickOutdoorActivity(
        _ activity: OutdoorActivityDurationSheet.ActivityKind,
        minutes: Int,
        intensity: StrengthWorkoutIntensity
    ) {
        guard minutes > 0 else { return }
        let exerciseID = activity == .walking
            ? OutdoorActivitySettings.walkingExerciseID
            : OutdoorActivitySettings.runningExerciseID
        guard let item = library.exercises.first(where: { $0.id == exerciseID }) else { return }

        workoutStore.logQuickCardio(item, minutes: minutes, on: selectedDate, intensity: intensity)
        if let estimate = StrengthWorkoutBurnEstimator.estimate(
            exercises: workoutStore.exercises(for: selectedDate),
            bodyWeightKg: currentBodyWeightKg,
            defaultWeightUnit: weightUnit,
            defaultRPEScale: workoutStore.preferences.rpeScale
        ) {
            _ = workoutStore.upsertCalculatedWorkout(
                on: selectedDate,
                caloriesBurned: estimate.calories,
                weightUnit: weightUnit
            )
        }
        AppHaptics.impact(.medium)
    }

    private func calculateBurn() {
        guard !isCalculatingBurn else { return }

        dismissSetKeyboard()

        guard StrengthWorkoutBurnEstimator.estimate(
            exercises: selectedExercises,
            bodyWeightKg: currentBodyWeightKg,
            defaultWeightUnit: weightUnit,
            defaultRPEScale: workoutStore.preferences.rpeScale
        ) != nil else {
            isNoPerformedSetAlertPresented = true
            return
        }

        let calculationDate = selectedDate
        let calculationWeightUnit = weightUnit
        isCalculatingBurn = true
        AppHaptics.impact(.medium)

        Task { @MainActor in
            // Keep the local calculation state visible long enough to read rather
            // than flashing past between two rendered frames.
            try? await Task.sleep(for: .milliseconds(450))

            // Use the same saved timer/set state that the store snapshots below.
            // A timer may have been resumed or discarded during the animation.
            guard let estimate = StrengthWorkoutBurnEstimator.estimate(
                exercises: workoutStore.exercises(for: calculationDate),
                bodyWeightKg: currentBodyWeightKg,
                defaultWeightUnit: calculationWeightUnit,
                defaultRPEScale: workoutStore.preferences.rpeScale
            ) else {
                isCalculatingBurn = false
                isNoPerformedSetAlertPresented = true
                return
            }

            withAnimation(.snappy(duration: 0.25)) {
                _ = workoutStore.upsertCalculatedWorkout(
                    on: calculationDate,
                    caloriesBurned: estimate.calories,
                    weightUnit: calculationWeightUnit
                )
            }
            isCalculatingBurn = false
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }

    private func dismissSetKeyboard() {
        guard focusedSetField != nil else { return }
        focusedSetField = nil
        dismissKeyboard()
    }

    private func revealFocusedSet(_ id: UUID, in proxy: ScrollViewProxy) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
            guard focusedSetField?.setID == id else { return }
            withAnimation(.snappy(duration: 0.25)) {
                proxy.scrollTo(id, anchor: .center)
            }
        }
    }

    private func requestWorkoutDeletion(_ exercise: StrengthPlannedExercise) {
        dismissSetKeyboard()
        pendingWorkoutDeletion = exercise
    }

    private func confirmWorkoutDeletion(_ exercise: StrengthPlannedExercise) {
        workoutStore.removeExercise(exercise.id, on: selectedDate)
        pendingWorkoutDeletion = nil
    }
}

// MARK: - 53-week diary strip

private struct WorkoutLogWeekStrip: View {
    @Binding var selectedDate: Date
    let workoutCountForDate: (Date) -> Int
    @AppStorage("weekStartsOnMonday") private var weekStartsOnMonday = true
    @State private var scrolledWeek: Int?

    private static let totalWeeks = 53
    private static let currentWeekIndex = totalWeeks - 1

    private var calendar: Calendar {
        var value = Calendar.current
        value.firstWeekday = weekStartsOnMonday ? 2 : 1
        return value
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 0) {
                ForEach(0..<Self.totalWeeks, id: \.self) { weekIndex in
                    weekRow(for: weekIndex)
                        .containerRelativeFrame(.horizontal)
                        .id(weekIndex)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .defaultScrollAnchor(.trailing)
        .scrollPosition(id: $scrolledWeek)
        .onAppear {
            if scrolledWeek == nil {
                scrolledWeek = boundedWeekIndex(for: selectedDate)
            }
        }
        .onChange(of: weekStartsOnMonday) { _, _ in
            scrolledWeek = boundedWeekIndex(for: selectedDate)
        }
        .onChange(of: selectedDate) { _, newValue in
            let target = boundedWeekIndex(for: newValue)
            if scrolledWeek != target {
                withAnimation(.snappy) { scrolledWeek = target }
            }
        }
        .padding(.vertical, 8)
        .flashPanel()
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Workout diary dates")
    }

    private func weekRow(for weekIndex: Int) -> some View {
        HStack(spacing: 0) {
            ForEach(weekDates(for: weekIndex), id: \.self) { date in
                dayTile(for: date)
            }
        }
    }

    private func dayTile(for date: Date) -> some View {
        let isSelected = calendar.isDate(date, inSameDayAs: selectedDate)
        let isToday = calendar.isDateInToday(date)
        let workoutCount = workoutCountForDate(date)

        return Button {
            AppHaptics.impact(.light)
            withAnimation(.snappy(duration: 0.3)) {
                selectedDate = date
            }
        } label: {
            VStack(spacing: 6) {
                Text(date.formatted(.dateTime.weekday(.narrow)))
                    .font(.system(.caption2, weight: .bold).width(.condensed))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(Color.workoutMutedText)

                Text(date.formatted(.dateTime.day()))
                    .font(.system(.body, weight: .black).width(.condensed))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .foregroundStyle(isSelected ? Color.workoutOnAccent : (isToday ? Color.workoutAccent : Color.workoutCharcoal))
                    .frame(maxWidth: .infinity, minHeight: 36)
                    .background {
                        if isSelected {
                            Rectangle()
                                .fill(Color.workoutAccent)
                        } else if isToday {
                            Rectangle()
                                .strokeBorder(Color.workoutAccent, lineWidth: 1)
                        }
                    }
                    .padding(.horizontal, 2)

                Rectangle()
                    .fill(workoutCount > 0 ? Color.workoutAccent : Color.clear)
                    .frame(width: 4, height: 4)
            }
        }
        .buttonStyle(.plain)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .frame(maxWidth: .infinity)
        .accessibilityLabel(date.formatted(date: .complete, time: .omitted))
        .accessibilityValue(calendarAccessibilityValue(workoutCount: workoutCount, isSelected: isSelected))
    }

    private func calendarAccessibilityValue(workoutCount: Int, isSelected: Bool) -> String {
        let count = String(localized: "\(workoutCount) workouts")
        guard isSelected else { return count }
        return String.localizedStringWithFormat(
            String(localized: String.LocalizationValue("%@, selected")),
            count
        )
    }

    private func weekDates(for weekIndex: Int) -> [Date] {
        let currentWeekStart = weekStart(for: .now)
        let weekOffset = weekIndex - Self.currentWeekIndex
        let weekStart = calendar.date(byAdding: .weekOfYear, value: weekOffset, to: currentWeekStart) ?? currentWeekStart
        return (0..<7).compactMap { calendar.date(byAdding: .day, value: $0, to: weekStart) }
    }

    private func boundedWeekIndex(for date: Date) -> Int {
        let days = calendar.dateComponents(
            [.day],
            from: weekStart(for: .now),
            to: weekStart(for: date)
        ).day ?? 0
        return min(max(Self.currentWeekIndex + (days / 7), 0), Self.currentWeekIndex)
    }

    private func weekStart(for date: Date) -> Date {
        let day = calendar.startOfDay(for: date)
        let weekday = calendar.component(.weekday, from: day)
        let daysBack = (weekday - calendar.firstWeekday + 7) % 7
        return calendar.date(byAdding: .day, value: -daysBack, to: day) ?? day
    }
}

// MARK: - Signature burn calculator and stats

private struct WorkoutLogBurnHero: View {
    let workoutCount: Int
    let setCount: Int
    let repCount: Int
    let caloriesBurned: Int?
    let isCalculatingBurn: Bool
    let calculateBurn: () -> Void
    let changeDay: (Int) -> Void

    var body: some View {
        VStack(spacing: 10) {
            WorkoutLogBurnButton(isCalculating: isCalculatingBurn, action: calculateBurn)

            WorkoutLogStatsStrip(
                setCount: setCount,
                workoutCount: workoutCount,
                repCount: repCount,
                caloriesBurned: caloriesBurned
            )
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .simultaneousGesture(daySwipeGesture)
    }

    private var daySwipeGesture: some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                guard let delta = WorkoutLogDaySwipeNavigation.dayDelta(for: value.translation) else { return }
                changeDay(delta)
            }
    }
}

private struct WorkoutLogBurnButton: View {
    let isCalculating: Bool
    let action: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if isCalculating {
                    ProgressView().tint(AppColors.flashRule)
                } else if !dynamicTypeSize.isAccessibilitySize {
                    Image(systemName: "flame.fill")
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(isCalculating ? "Calculating…" : "Calculate")
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Text("CALORIE BURN")
                        .font(.caption.weight(.bold).width(.condensed))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .buttonStyle(FlashActionButtonStyle())
        .disabled(isCalculating)
        .accessibilityLabel("Calculate calorie burn")
        .accessibilityValue(isCalculating ? String(localized: "Calculating") : String(localized: "Ready"))
        .accessibilityHint("Uses saved exercise duration and intensity, or strength sets, repetitions and load, with current body weight")
    }
}

private struct WorkoutLogStatsStrip: View {
    let setCount: Int
    let workoutCount: Int
    let repCount: Int
    let caloriesBurned: Int?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @AppStorage(EnergyUnit.storageKey) private var energyUnit = EnergyUnit.defaultUnit

    var body: some View {
        ViewThatFits(in: .horizontal) {
            if !dynamicTypeSize.isAccessibilitySize {
                HStack(spacing: 6) {
                    metrics
                }
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 116), spacing: 6)], spacing: 6) {
                metrics
            }
        }
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder private var metrics: some View {
        metric(label: "Sets", value: setCount.formatted(), systemImage: "checklist", active: setCount > 0)
        metric(label: "Workouts", value: workoutCount.formatted(), systemImage: "dumbbell.fill", active: workoutCount > 0)
        metric(label: "Reps", value: repCount.formatted(), systemImage: "repeat", active: repCount > 0)
        metric(
            label: "Burn",
            value: caloriesBurned.map { energyUnit.format(kcal: $0) } ?? "-- \(energyUnit.symbol)",
            systemImage: "flame.fill",
            active: caloriesBurned != nil
        )
    }

    private func metric(label: String, value: String, systemImage: String, active: Bool) -> some View {
        let localizedLabel = LocalizedDisplayText.text(label)
        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 11, weight: .heavy))
                    .foregroundStyle(active ? Color.workoutAccent : Color.workoutMutedText.opacity(0.72))
                    .frame(width: 14, height: 14)
                Text(localizedLabel)
                    .font(.system(.caption2, weight: .bold).width(.condensed))
                    .foregroundStyle(Color.workoutMutedText)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Text(value)
                .font(.system(.headline, weight: .black).width(.condensed))
                .foregroundStyle(active ? Color.workoutAccent : Color.workoutCharcoal)
                .contentTransition(.numericText())
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(8)
        .frame(minWidth: 78, maxWidth: .infinity, minHeight: 60, alignment: .leading)
        .flashPanel()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(localizedLabel)
        .accessibilityValue(value)
    }
}

// MARK: - Planned workout cards

private struct WorkoutLogSetFocus: Hashable {
    enum Field: Hashable { case weight, reps, rpe }

    let exerciseID: UUID
    let setID: UUID
    let field: Field
}

private struct WorkoutLogExerciseHistoryRequest: Identifiable {
    let itemID: String
    let name: String

    var id: String { itemID }
}

private struct WorkoutLogExerciseCard: View {
    let exercise: StrengthPlannedExercise
    let weightUnit: WeightUnit
    let rpeScale: StrengthWorkoutRPEScale
    let isSaved: Bool
    let lastTimeSummary: String?
    let focusedField: FocusState<WorkoutLogSetFocus?>.Binding
    /// VoiceOver actions; nil at the first/last position.
    let moveUp: (() -> Void)?
    let moveDown: (() -> Void)?
    let openDetail: () -> Void
    let showHistory: () -> Void
    let toggleSaved: () -> Void
    let removeExercise: () -> Void
    let timerAction: (StrengthExerciseTimerAction) -> Void
    let setEffort: (StrengthWorkoutIntensity) -> Void
    let updateSetCount: (Int) -> Void
    let updateWeight: (UUID, String) -> Void
    let updateReps: (UUID, String) -> Void
    let updateRPE: (UUID, String) -> Void
    /// The set whose rest is counting down, when it belongs to this exercise.
    var restingSetID: UUID? = nil
    /// Nil when rest is unavailable (rest timer off, or not today's log).
    var startRest: ((UUID) -> Void)? = nil
    @Environment(\.colorScheme) private var colorScheme

    private var isCardio: Bool { exercise.isCardio }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button(action: openDetail) {
                HStack(alignment: .center, spacing: 12) {
                    AnimatedExerciseVisual(
                        exerciseName: exercise.name,
                        imagePaths: exercise.imagePaths,
                        height: 64,
                        fillsWidth: false,
                        allowsDerivedImageLookup: false,
                        animatesFrames: false
                    )
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(Color.workoutHairline.opacity(0.48), lineWidth: 0.7)
                    }
                    .clipped()
                    .layoutPriority(0)
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(exercise.name)
                            .font(.system(.headline, design: .rounded, weight: .semibold))
                            .foregroundStyle(Color.workoutCharcoal)
                            .lineLimit(2)

                        Text("\(exercise.primaryMuscles.joined(separator: ", ")) - \(exercise.rawEquipment)")
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(Color.workoutMutedText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(1)

                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.workoutMutedText.opacity(0.72))
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(exercise.name), \(exercise.primaryMuscles.joined(separator: ", ")), \(exercise.rawEquipment)")
            .accessibilityHint("Opens exercise instructions")
            .accessibilityActions {
                if let moveUp { Button("Move up", action: moveUp) }
                if let moveDown { Button("Move down", action: moveDown) }
            }

            if !isCardio {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if let lastTimeSummary {
                        Text("Last time: \(lastTimeSummary)")
                            .font(.system(.caption, design: .rounded, weight: .semibold))
                            .foregroundStyle(Color.workoutMutedText)
                            .lineLimit(2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        Spacer(minLength: 0)
                    }

                    Button(action: showHistory) {
                        Text("History")
                            .font(.system(.caption, design: .rounded, weight: .heavy))
                            .foregroundStyle(Color.workoutAccent)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Shows past logged sets for this exercise")
                }
            }

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 6) {
                    Image(systemName: isCardio ? "figure.run" : "list.number")
                        .font(.caption.weight(.heavy))
                        .foregroundStyle(Color.workoutMutedText)

                    if isCardio {
                        // Cardio has no RPE fields, so effort is chosen directly (Moderate by default).
                        effortMenu
                    }

                    Spacer(minLength: 0)

                    if !isCardio {
                        Stepper(
                            value: Binding(get: { exercise.sets.count }, set: updateSetCount),
                            in: 1...12
                        ) {
                            Text("\(exercise.sets.count)")
                                .font(.caption.weight(.heavy))
                                .foregroundStyle(Color.workoutCharcoal)
                                .lineLimit(1)
                        }
                        .fixedSize()
                        .accessibilityLabel("Sets")
                        .accessibilityValue("\(exercise.sets.count)")
                        .accessibilityHint("Adjust from one to twelve sets")

                    }

                    HStack(spacing: 0) {
                        WorkoutExerciseTimerControls(
                            exerciseID: exercise.id,
                            exerciseName: exercise.name,
                            timer: exercise.timer,
                            action: timerAction
                        )
                        Button(action: toggleSaved) {
                            Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                                .font(.system(size: 17, weight: .bold))
                                .frame(width: 36, height: 36)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .foregroundStyle(isSaved ? Color.workoutAccent : Color.workoutMutedText.opacity(0.72))
                        .accessibilityLabel(isSaved ? "Unsave exercise" : "Save exercise")
                        .accessibilityHint(isSaved ? "Removes this exercise from Saved" : "Adds this exercise to Saved")

                        Button(role: .destructive, action: removeExercise) {
                            Image(systemName: "trash")
                                .font(.system(size: 17, weight: .bold))
                                .frame(width: 36, height: 36)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .foregroundStyle(Color(red: 0.78, green: 0.14, blue: 0.12))
                        .accessibilityLabel("Remove exercise")
                        .accessibilityHint("Removes this exercise from the selected day")
                    }
                }

                if !isCardio {
                    VStack(spacing: 0) {
                        ForEach(Array(exercise.sets.enumerated()), id: \.element.id) { index, set in
                            WorkoutLogSetRow(
                                exerciseID: exercise.id,
                                setIndex: index,
                                set: set,
                                rpeScale: rpeScale,
                                weightUnit: weightUnit,
                                focusedField: focusedField,
                                updateWeight: { updateWeight(set.id, $0) },
                                updateReps: { updateReps(set.id, $0) },
                                updateRPE: { updateRPE(set.id, $0) },
                                isResting: restingSetID == set.id,
                                startRest: startRest.map { start -> () -> Void in { start(set.id) } }
                            )
                            .id(set.id)

                            if index < exercise.sets.count - 1 {
                                Divider()
                                    .overlay(Color.workoutHairline.opacity(0.5))
                                    .padding(.leading, 64)
                            }
                        }
                    }
                }


            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.workoutPanel, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.82), lineWidth: 0.8)
        }
        .shadow(color: shadowColor, radius: colorScheme == .light ? 0 : 12, x: 0, y: colorScheme == .light ? 0 : 7)
    }

    private var shadowColor: Color {
        colorScheme == .light ? .clear : Color.black.opacity(0.22)
    }

    private var effortMenu: some View {
        let effort = StrengthWorkoutBurnEstimator.timerIntensity(for: exercise, defaultRPEScale: rpeScale)
        return FlashMenu(title: Text("Effort"), items: {
            StrengthWorkoutIntensity.allCases.map { option in
                FlashMenuItem(Text(option.localizedTitle), isSelected: effort == option) {
                    if option != effort { setEffort(option) }
                }
            }
        }) {
            HStack(spacing: 3) {
                Text("Effort: \(effort.localizedTitle)")
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2.weight(.bold))
            }
            .font(.system(.caption, design: .rounded, weight: .heavy))
            .foregroundStyle(Color.workoutAccent)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(minHeight: 44)
            .flashPanel()
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Effort")
        .accessibilityValue(effort.localizedTitle)
    }
}

/// Compact row used while reordering: large, labeled arrows instead of a
/// drag-only gesture, so the order can be changed one step at a time.
private struct WorkoutLogReorderRow: View {
    let exercise: StrengthPlannedExercise
    let position: Int
    let canMoveUp: Bool
    let canMoveDown: Bool
    let moveUp: () -> Void
    let moveDown: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            HStack(spacing: 12) {
                Text(position, format: .number)
                    .font(.system(.title3, design: .rounded, weight: .heavy))
                    .foregroundStyle(Color.workoutAccent)
                    .frame(minWidth: 26)

                AnimatedExerciseVisual(
                    exerciseName: exercise.name,
                    imagePaths: exercise.imagePaths,
                    height: 48,
                    fillsWidth: false,
                    allowsDerivedImageLookup: false,
                    animatesFrames: false
                )
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .clipped()
                .accessibilityHidden(true)

                Text(exercise.name)
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .combine)

            moveButton(systemImage: "chevron.up", enabled: canMoveUp, action: moveUp)
                .accessibilityLabel(Text("Move \(exercise.name) up"))
            moveButton(systemImage: "chevron.down", enabled: canMoveDown, action: moveDown)
                .accessibilityLabel(Text("Move \(exercise.name) down"))
        }
        .padding(.vertical, 10)
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.workoutPanel, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.82), lineWidth: 0.8)
        }
    }

    private func moveButton(systemImage: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .bold))
                .frame(width: 48, height: 48)
                .background(Color.workoutAccent.opacity(enabled ? 0.14 : 0.05), in: Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(enabled ? Color.workoutAccent : Color.workoutMutedText.opacity(0.4))
        .disabled(!enabled)
    }
}

private struct WorkoutExerciseTimerControls: View {
    let exerciseID: UUID
    let exerciseName: String
    let timer: StrengthExerciseTimer?
    let action: (StrengthExerciseTimerAction) -> Void
    @State private var showingActions = false

    private var isRunning: Bool { timer?.isRunning == true }
    private var isSaved: Bool { timer?.isSaved == true }
    /// Choosing an effort first stores an empty timer; it still reads as "Start timer".
    private var hasStarted: Bool { timer?.hasStarted == true }

    var body: some View {
        Button {
            if !hasStarted { action(.start) } else { showingActions = true }
        } label: {
            VStack(spacing: 1) {
                Image(systemName: isRunning ? "stopwatch.fill" : "stopwatch")
                    .font(.system(size: 17, weight: .bold))
                if hasStarted {
                    TimelineView(.animation(minimumInterval: 1, paused: !isRunning)) { context in
                        Text(durationText(at: context.date))
                            .font(.system(size: 9, weight: .semibold, design: .rounded).monospacedDigit())
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                            .accessibilityIdentifier("workout.timer.\(exerciseID).elapsed")
                    }
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(!hasStarted ? Color.workoutMutedText.opacity(0.72) : Color.workoutAccent)
        .accessibilityLabel("\(!hasStarted ? "Start timer" : "Timer options") for \(exerciseName)")
        .accessibilityValue(!hasStarted ? "" : "\(durationText(at: .now)), \(isRunning ? "running" : (isSaved ? "saved" : "paused"))")
        .accessibilityIdentifier("workout.timer.\(exerciseID).primary")
        .confirmationDialog("Timer for \(exerciseName)", isPresented: $showingActions, titleVisibility: .visible) {
            Button(isRunning ? "Pause" : "Resume") { action(isRunning ? .pause : .resume) }
            if !isSaved {
                Button("Stop & save") { action(.stop) }
            }
            Button("Restart timer") { action(.restart) }
            Button("Discard timer", role: .destructive) { action(.discard) }
            Button("Cancel", role: .cancel) { }
        }
    }

    private func durationText(at date: Date) -> String {
        let elapsed = timer?.elapsedSeconds(at: date) ?? 0
        let seconds = elapsed.isFinite ? Int(min(max(0, elapsed), Double(Int.max / 2))) : 0
        if seconds >= 3_600 {
            return "\(seconds / 3_600):" + String(format: "%02d:%02d", seconds / 60 % 60, seconds % 60)
        }
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
}

private struct WorkoutLogSetRow: View {
    let exerciseID: UUID
    let setIndex: Int
    let set: StrengthPlannedSet
    let rpeScale: StrengthWorkoutRPEScale
    let weightUnit: WeightUnit
    let focusedField: FocusState<WorkoutLogSetFocus?>.Binding
    let updateWeight: (String) -> Void
    let updateReps: (String) -> Void
    let updateRPE: (String) -> Void
    var isResting = false
    var startRest: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            Text("Set \(setIndex + 1)")
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .foregroundStyle(Color.workoutMutedText)
                .lineLimit(1)
                .minimumScaleFactor(0.82)
                .frame(width: 46, alignment: .leading)

            WorkoutLogSetValueField(
                placeholder: weightUnit.rawValue,
                text: Binding(get: { set.displayWeight(in: weightUnit) }, set: updateWeight),
                keyboardType: .decimalPad,
                focus: WorkoutLogSetFocus(exerciseID: exerciseID, setID: set.id, field: .weight),
                focusedField: focusedField
            )
            .frame(maxWidth: .infinity)

            WorkoutLogSetValueField(
                placeholder: "Reps",
                text: Binding(get: { set.reps }, set: updateReps),
                keyboardType: .numberPad,
                focus: WorkoutLogSetFocus(exerciseID: exerciseID, setID: set.id, field: .reps),
                focusedField: focusedField
            )
            .frame(maxWidth: .infinity)

            WorkoutLogSetValueField(
                placeholder: "RPE",
                text: Binding(get: { set.rpe }, set: updateRPE),
                keyboardType: rpeScale.allowsDecimalInput ? .decimalPad : .numberPad,
                focus: WorkoutLogSetFocus(exerciseID: exerciseID, setID: set.id, field: .rpe),
                focusedField: focusedField
            )
            .frame(maxWidth: .infinity)

            if let startRest {
                WorkoutRestSetButton(setNumber: setIndex + 1, isResting: isResting, action: startRest)
            }
        }
        .padding(.vertical, 7)
        .contentShape(Rectangle())
        .accessibilityHint("Opens set weight, reps and RPE input")
    }
}

private struct WorkoutLogSetValueField: View {
    let placeholder: String
    @Binding var text: String
    let keyboardType: UIKeyboardType
    let focus: WorkoutLogSetFocus
    let focusedField: FocusState<WorkoutLogSetFocus?>.Binding

    var body: some View {
        // Store bindings normalize text as it changes. Let UIKit keep the caret
        // instead of applying a deferred String.Index from a different value.
        TextField(placeholder, text: $text)
            .keyboardType(keyboardType)
            .textFieldStyle(.plain)
            .font(.system(.subheadline, design: .rounded, weight: .bold).monospacedDigit())
            .foregroundStyle(Color.workoutCharcoal)
            .multilineTextAlignment(.center)
            .focused(focusedField, equals: focus)
            .padding(.horizontal, 10)
            .frame(height: 36)
            .background(Color.workoutCard.opacity(0.74), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .stroke(Color.workoutHairline.opacity(0.4), lineWidth: 0.6)
                    .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
    }
}

private struct WorkoutLogEmptyRoutineRow: View {
    let splitTitle: String
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "plus.circle")
                .font(.title2.weight(.semibold))
                .foregroundStyle(Color.workoutAccent)

            VStack(alignment: .leading, spacing: 3) {
                Text("No workouts logged")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                Text(String.localizedStringWithFormat(
                    String(localized: String.LocalizationValue("Use + to pick %@ workouts for this day")),
                    LocalizedDisplayText.text(splitTitle)
                ))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.workoutMutedText)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.workoutPanel.opacity(0.94), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.workoutHairline.opacity(0.95), lineWidth: 1)
        }
        .shadow(color: shadowColor, radius: colorScheme == .light ? 0 : 10, x: 0, y: colorScheme == .light ? 0 : 5)
        .accessibilityElement(children: .combine)
    }

    private var shadowColor: Color {
        colorScheme == .light ? .clear : Color.black.opacity(0.16)
    }
}

// MARK: - Exercise picker

enum WorkoutLogPickerSource: String, CaseIterable, Identifiable {
    case dataset = "Dataset"
    case saved = "Saved"
    /// Exercises the user created themselves.
    case mine = "Mine"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dataset: return String(localized: "Dataset")
        case .saved: return String(localized: "Saved")
        case .mine: return String(localized: "Mine")
        }
    }

    func includes(itemID: String, savedIDs: Set<String>) -> Bool {
        switch self {
        case .dataset: return true
        case .saved: return savedIDs.contains(itemID)
        case .mine: return UserExercise.isUserExercise(itemID)
        }
    }
}

private struct WorkoutLogPickerContext: Hashable {
    static let all = WorkoutLogPickerContext(title: "All Workouts", muscles: [])
    static let saved = WorkoutLogPickerContext(title: "Saved", muscles: [])

    let title: String
    let muscles: Set<String>

    var id: String { title }
}

private struct WorkoutLogPickerRequest: Identifiable {
    let id = UUID()
    let context: WorkoutLogPickerContext
    let initialSource: WorkoutLogPickerSource
}

private struct WorkoutLogPickerFilterState: Codable, Equatable {
    var searchText = ""
    var levels: Set<String> = []
    var equipment: Set<String> = []
    var primaryMuscles: Set<String> = []
    var secondaryMuscles: Set<String> = []
    var forces: Set<String> = []
    var mechanics: Set<String> = []
    var categories: Set<String> = []
    var sort: ExerciseLibrarySort = .name
}

private enum WorkoutLogPickerFilterStateStore {
    static func load(contextID: String) -> WorkoutLogPickerFilterState {
        guard let data = UserDefaults.standard.data(forKey: key(contextID)),
              let state = try? JSONDecoder().decode(WorkoutLogPickerFilterState.self, from: data)
        else { return WorkoutLogPickerFilterState() }
        return state
    }

    static func save(_ state: WorkoutLogPickerFilterState, contextID: String) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        UserDefaults.standard.set(data, forKey: key(contextID))
    }

    private static func key(_ contextID: String) -> String {
        "fudai.workouts.picker.filter.v1.\(contextID)"
    }
}

private struct WorkoutPickerEditingExerciseRequest: Identifiable {
    let id: String
}

private struct WorkoutLogExercisePickerSheet: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    let request: WorkoutLogPickerRequest
    let selectedDate: Date
    let onDone: () -> Void

    @AppStorage("fudai.workouts.picker.source.v1") private var sourceRaw = WorkoutLogPickerSource.dataset.rawValue
    @State private var searchText = ""
    @State private var debouncedSearchText = ""
    @State private var displayExercises: [ExerciseLibraryItem] = []
    @State private var searchDebounceTask: Task<Void, Never>?
    @State private var filterPersistTask: Task<Void, Never>?
    @State private var selectedLevels: Set<String> = []
    @State private var selectedEquipment: Set<String> = []
    @State private var selectedPrimaryMuscles: Set<String> = []
    @State private var selectedSecondaryMuscles: Set<String> = []
    @State private var selectedForces: Set<String> = []
    @State private var selectedMechanics: Set<String> = []
    @State private var selectedCategories: Set<String> = []
    @State private var selectedSort: ExerciseLibrarySort = .name
    @State private var previewItem: ExerciseLibraryItem?
    @State private var isCreateExercisePresented = false
    @State private var editingExerciseRequest: WorkoutPickerEditingExerciseRequest?

    private var library: ExerciseLibraryService { workoutStore.exerciseLibrary }

    init(request: WorkoutLogPickerRequest, selectedDate: Date, onDone: @escaping () -> Void) {
        self.request = request
        self.selectedDate = selectedDate
        self.onDone = onDone
        let state = WorkoutLogPickerFilterStateStore.load(contextID: request.context.id)
        _searchText = State(initialValue: state.searchText)
        _debouncedSearchText = State(initialValue: state.searchText)
        _selectedLevels = State(initialValue: state.levels)
        _selectedEquipment = State(initialValue: state.equipment)
        _selectedPrimaryMuscles = State(initialValue: state.primaryMuscles)
        _selectedSecondaryMuscles = State(initialValue: state.secondaryMuscles)
        _selectedForces = State(initialValue: state.forces)
        _selectedMechanics = State(initialValue: state.mechanics)
        _selectedCategories = State(initialValue: state.categories)
        _selectedSort = State(initialValue: state.sort)
    }

    private var isSavedContext: Bool {
        request.context.id == WorkoutLogPickerContext.saved.id
    }

    private var showsSourcePicker: Bool { !isSavedContext }

    private var source: WorkoutLogPickerSource {
        isSavedContext ? .saved : (WorkoutLogPickerSource(rawValue: sourceRaw) ?? .dataset)
    }

    private var sourceBinding: Binding<WorkoutLogPickerSource> {
        Binding(
            get: { source },
            set: { sourceRaw = $0.rawValue }
        )
    }

    private var hidesPrimaryFilter: Bool {
        (workoutStore.preferences.split == .fullBody || workoutStore.preferences.split == .custom)
            && !request.context.muscles.isEmpty
    }

    private var filterState: WorkoutLogPickerFilterState {
        WorkoutLogPickerFilterState(
            searchText: searchText,
            levels: selectedLevels,
            equipment: selectedEquipment,
            primaryMuscles: selectedPrimaryMuscles,
            secondaryMuscles: selectedSecondaryMuscles,
            forces: selectedForces,
            mechanics: selectedMechanics,
            categories: selectedCategories,
            sort: selectedSort
        )
    }

    private var hasActiveFilters: Bool {
        !searchText.isEmpty
            || !selectedLevels.isEmpty
            || !selectedEquipment.isEmpty
            || !selectedPrimaryMuscles.isEmpty
            || !selectedSecondaryMuscles.isEmpty
            || !selectedForces.isEmpty
            || !selectedMechanics.isEmpty
            || !selectedCategories.isEmpty
            || selectedSort != .name
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if showsSourcePicker {
                        Picker("Source", selection: sourceBinding) {
                            ForEach(WorkoutLogPickerSource.allCases) { source in
                                Text(source.title).tag(source)
                            }
                        }
                        .pickerStyle(.segmented)
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 4, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                    }

                    filterStrip
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 6, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)

                    resultsHeader
                        .listRowInsets(EdgeInsets(top: 0, leading: 20, bottom: 10, trailing: 20))
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }

                Section {
                    if displayExercises.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(emptyStateMessage)
                                .foregroundStyle(Color.workoutMutedText)
                            if source != .saved {
                                Button {
                                    isCreateExercisePresented = true
                                } label: {
                                    Label("Create exercise", systemImage: "plus.circle.fill")
                                        .font(.subheadline.weight(.semibold))
                                }
                                .buttonStyle(.borderless)
                                .foregroundStyle(Color.workoutAccent)
                            }
                        }
                        .listRowBackground(Color.workoutPanel.opacity(0.22))
                    } else {
                        ForEach(displayExercises.prefix(120)) { item in
                            WorkoutLogPickerRow(
                                item: item,
                                isSelected: workoutStore.containsExercise(item.id, on: selectedDate),
                                isSaved: workoutStore.savedExerciseIDs.contains(item.id),
                                action: {
                                    workoutStore.toggleExercise(item, on: selectedDate)
                                },
                                toggleSaved: {
                                    workoutStore.toggleSaved(item.id)
                                },
                                previewAction: {
                                    dismissKeyboard()
                                    previewItem = item
                                }
                            )
                            .listRowBackground(
                                Color.workoutPanel.opacity(workoutStore.containsExercise(item.id, on: selectedDate) ? 0.28 : 0.18)
                            )
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                bookmarkSwipe(for: item)
                            }
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                bookmarkSwipe(for: item)
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .listStyle(.plain)
            .contentMargins(.top, 0, for: .scrollContent)
            .background(Color.workoutBackground)
            .scrollDismissesKeyboard(.immediately)
            .contentShape(Rectangle())
            .onTapGesture {
                dismissKeyboard()
            }
            .searchable(text: $searchText, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search workouts")
            .listSectionSpacing(0)
            .navigationTitle("Add \(request.context.title)")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(item: $previewItem) { item in
                ExerciseLibraryDetailView(
                    item: item,
                    onEdit: UserExercise.isUserExercise(item.id) ? {
                        previewItem = nil
                        editingExerciseRequest = WorkoutPickerEditingExerciseRequest(id: item.id)
                    } : nil
                )
                    .safeAreaInset(edge: .bottom, spacing: 0) {
                        WorkoutLogPreviewActionBar(
                            isSelected: workoutStore.containsExercise(item.id, on: selectedDate),
                            action: {
                                guard libraryItemStillExists(item.id) else {
                                    previewItem = nil
                                    return
                                }
                                if let fresh = workoutStore.exerciseLibrary.exercises.first(where: { $0.id == item.id }) {
                                    workoutStore.toggleExercise(fresh, on: selectedDate)
                                }
                            }
                        )
                    }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if source != .saved {
                        Button {
                            isCreateExercisePresented = true
                        } label: {
                            Image(systemName: "plus")
                        }
                        .accessibilityLabel("Create exercise")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onDone)
                        .font(.headline.weight(.heavy))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
            .flashSheet(isPresented: $isCreateExercisePresented) {
                UserExerciseEditorView { item in
                    workoutStore.toggleExercise(item, on: selectedDate)
                }
            }
            .flashSheet(item: $editingExerciseRequest) { request in
                UserExerciseEditorView(
                    existingItemID: request.id,
                    onSaved: { saved in
                        refreshDisplayExercises()
                        if previewItem?.id == saved.id {
                            previewItem = saved
                        }
                    },
                    onDeleted: {
                        if previewItem?.id == request.id {
                            previewItem = nil
                        }
                        refreshDisplayExercises()
                    }
                )
            }
            .keepsWorkoutLogToolbarDuringSearch()
        }
        .onAppear {
            refreshDisplayExercises()
            normalizePrimaryFilterSelection()
            normalizeEquipmentFilterSelection()
        }
        .onDisappear {
            filterPersistTask?.cancel()
            WorkoutLogPickerFilterStateStore.save(filterState, contextID: request.context.id)
        }
        .onChange(of: searchText) { _, newValue in
            WorkoutSearchDebounce.schedule(task: &searchDebounceTask) {
                debouncedSearchText = newValue
                refreshDisplayExercises()
            }
            scheduleFilterPersist()
        }
        .onChange(of: selectedLevels) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedEquipment) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedPrimaryMuscles) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedSecondaryMuscles) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedForces) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedMechanics) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedCategories) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: selectedSort) { _, _ in refreshDisplayExercises(); scheduleFilterPersist() }
        .onChange(of: sourceRaw) { _, _ in refreshDisplayExercises() }
        .onChange(of: request.context.muscles) {
            refreshDisplayExercises()
            normalizePrimaryFilterSelection()
        }
        .onChange(of: hidesPrimaryFilter) {
            normalizePrimaryFilterSelection()
        }
        .onChange(of: availableEquipment) {
            normalizeEquipmentFilterSelection()
            refreshDisplayExercises()
        }
        .onChange(of: workoutStore.savedExerciseIDs) { _, _ in refreshDisplayExercises() }
        .onChange(of: userExercisesFingerprint) { _, _ in refreshDisplayExercises() }
    }

    private var userExercisesFingerprint: String {
        workoutStore.userExercises.map {
            "\($0.itemID)|\($0.name)|\($0.imagePaths.joined(separator: ","))"
        }.joined(separator: ";")
    }

    private var emptyStateMessage: LocalizedStringKey {
        if source == .saved { return "No saved workouts yet." }
        if source == .mine && workoutStore.userExercises.isEmpty { return "No custom exercises yet." }
        return "No matching exercises."
    }

    private func libraryItemStillExists(_ itemID: String) -> Bool {
        workoutStore.exerciseLibrary.exercises.contains { $0.id == itemID }
    }

    private func refreshDisplayExercises() {
        // The profile equipment preference scopes the catalog, but must not hide the
        // user's own exercises (often "Unspecified" equipment) in the Mine list.
        let implicitEquipment: Set<String> = source == .mine ? [] : Set(availableEquipment)
        let effectiveEquipment = selectedEquipment.isEmpty
            ? implicitEquipment
            : selectedEquipment
        let filtered = library.filtered(
            levels: selectedLevels,
            rawEquipment: effectiveEquipment,
            primaryMuscles: selectedPrimaryMuscles,
            secondaryMuscles: selectedSecondaryMuscles,
            forces: selectedForces,
            mechanics: selectedMechanics,
            categories: selectedCategories,
            sort: selectedSort,
            searchText: debouncedSearchText
        )

        displayExercises = filtered.filter { item in
            let matchesContext = request.context.muscles.isEmpty
                || item.primaryMuscles.contains(where: request.context.muscles.contains)
                || item.secondaryMuscles.contains(where: request.context.muscles.contains)
            let matchesSource = source.includes(itemID: item.id, savedIDs: workoutStore.savedExerciseIDs)
            return matchesContext && matchesSource
        }
    }

    private func scheduleFilterPersist() {
        let snapshot = filterState
        let contextID = request.context.id
        WorkoutSearchDebounce.schedule(
            task: &filterPersistTask,
            delayNanoseconds: WorkoutSearchDebounce.persistDelayNanoseconds
        ) {
            WorkoutLogPickerFilterStateStore.save(snapshot, contextID: contextID)
        }
    }

    private func bookmarkSwipe(for item: ExerciseLibraryItem) -> some View {
        Button {
            workoutStore.toggleSaved(item.id)
        } label: {
            let isSaved = workoutStore.savedExerciseIDs.contains(item.id)
            Label(
                isSaved ? "Unsave" : "Save",
                systemImage: isSaved ? "bookmark.slash.fill" : "bookmark.fill"
            )
        }
        .tint(Color.workoutAccent)
    }

    private var resultsHeader: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(displayExercises.count) \(displayExercises.count == 1 ? "exercise" : "exercises")")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .textCase(nil)

                Text(selectedSort.title)
                    .font(.caption)
                    .foregroundStyle(Color.workoutMutedText)
                    .textCase(nil)
            }

            Spacer()

            Button(action: resetFilters) {
                Label("Reset", systemImage: "arrow.counterclockwise")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(hasActiveFilters ? Color.workoutInferno : Color.workoutMutedText)
                    .lineLimit(1)
                    .padding(.horizontal, 11)
                    .frame(height: 34)
                    .background(
                        (hasActiveFilters ? Color.workoutInferno : Color.workoutPanel).opacity(hasActiveFilters ? 0.10 : 0.22),
                        in: Capsule()
                    )
                    .overlay {
                        Capsule()
                            .stroke(
                                (hasActiveFilters ? Color.workoutInferno : Color.workoutHairline).opacity(hasActiveFilters ? 0.28 : 0.24),
                                lineWidth: 0.5
                            )
                    }
            }
            .disabled(!hasActiveFilters)
            .buttonStyle(.plain)
            .workoutPressable()

            Menu {
                ForEach(ExerciseLibrarySort.allCases) { sort in
                    menuChoice(sort.title, isSelected: selectedSort == sort) {
                        selectedSort = sort
                    }
                }
            } label: {
                Label("Sort", systemImage: "arrow.up.arrow.down")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(selectedSort == .name ? Color.workoutMutedText : Color.workoutAccent)
                    .lineLimit(1)
                    .padding(.horizontal, 11)
                    .frame(height: 34)
                    .background(Color.workoutPanel.opacity(selectedSort == .name ? 0.30 : 0.46), in: Capsule())
                    .overlay {
                        Capsule()
                            .stroke(
                                (selectedSort == .name ? Color.workoutHairline : Color.workoutAccent).opacity(0.32),
                                lineWidth: 0.5
                            )
                    }
            }
            .buttonStyle(.plain)
            .workoutPressable()
        }
    }

    private var filterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 9) {
                if !hidesPrimaryFilter {
                    filterMenu(
                        title: "Primary",
                        value: primaryFilterTitle,
                        systemImage: "scope",
                        isActive: !selectedPrimaryMuscles.isEmpty
                    ) {
                        menuChoice("All Primary (\(contextPrimaryMuscles.count))", isSelected: selectedPrimaryMuscles.isEmpty) {
                            selectedPrimaryMuscles.removeAll()
                        }
                        ForEach(contextPrimaryMuscles, id: \.self) { value in
                            muscleMenuChoice(value, muscles: [value], isSelected: selectedPrimaryMuscles.contains(value)) {
                                selectedPrimaryMuscles = [value]
                            }
                        }
                    }
                }

                filterMenu(
                    title: "Secondary",
                    value: selectionTitle(selectedSecondaryMuscles),
                    systemImage: "scope",
                    isActive: !selectedSecondaryMuscles.isEmpty
                ) {
                    menuChoice("All Secondary", isSelected: selectedSecondaryMuscles.isEmpty) { selectedSecondaryMuscles.removeAll() }
                    ForEach(library.availableSecondaryMuscles, id: \.self) { value in
                        muscleMenuChoice(value, muscles: [value], isSelected: selectedSecondaryMuscles.contains(value)) {
                            selectedSecondaryMuscles = [value]
                        }
                    }
                }

                filterMenu(
                    title: "Equipment",
                    value: equipmentFilterTitle,
                    systemImage: "dumbbell.fill",
                    isActive: !selectedEquipment.isEmpty
                ) {
                    menuChoice("All Equipment (\(availableEquipment.count))", isSelected: selectedEquipment.isEmpty) {
                        selectedEquipment.removeAll()
                    }
                    ForEach(availableEquipment, id: \.self) { value in
                        menuChoice(value, isSelected: selectedEquipment.contains(value)) { selectedEquipment = [value] }
                    }
                }

                filterMenu(
                    title: "Level",
                    value: selectionTitle(selectedLevels),
                    systemImage: "chart.bar.fill",
                    isActive: !selectedLevels.isEmpty
                ) {
                    menuChoice("All Levels", isSelected: selectedLevels.isEmpty) { selectedLevels.removeAll() }
                    ForEach(library.availableLevels, id: \.self) { value in
                        menuChoice(value, isSelected: selectedLevels.contains(value)) { selectedLevels = [value] }
                    }
                }

                filterMenu(
                    title: "Force",
                    value: selectionTitle(selectedForces),
                    systemImage: "arrow.left.arrow.right",
                    isActive: !selectedForces.isEmpty
                ) {
                    menuChoice("All Forces", isSelected: selectedForces.isEmpty) { selectedForces.removeAll() }
                    ForEach(library.availableForces, id: \.self) { value in
                        menuChoice(value, isSelected: selectedForces.contains(value)) { selectedForces = [value] }
                    }
                }

                filterMenu(
                    title: "Mechanic",
                    value: selectionTitle(selectedMechanics),
                    systemImage: "gearshape",
                    isActive: !selectedMechanics.isEmpty
                ) {
                    menuChoice("All Mechanics", isSelected: selectedMechanics.isEmpty) { selectedMechanics.removeAll() }
                    ForEach(library.availableMechanics, id: \.self) { value in
                        menuChoice(value, isSelected: selectedMechanics.contains(value)) { selectedMechanics = [value] }
                    }
                }

                filterMenu(
                    title: "Category",
                    value: selectionTitle(selectedCategories),
                    systemImage: "tag",
                    isActive: !selectedCategories.isEmpty
                ) {
                    menuChoice("All Categories", isSelected: selectedCategories.isEmpty) { selectedCategories.removeAll() }
                    ForEach(library.availableCategoryCounts) { value in
                        menuChoice(value.category, isSelected: selectedCategories.contains(value.category)) {
                            selectedCategories = [value.category]
                        }
                    }
                }
            }
            .padding(.vertical, 1)
        }
    }

    private var contextPrimaryMuscles: [String] {
        guard !request.context.muscles.isEmpty else { return library.availablePrimaryMuscles }
        return library.availablePrimaryMuscles.filter(request.context.muscles.contains)
    }

    private var availableEquipment: [String] {
        let preferred = workoutStore.preferences.equipment
        guard !preferred.isEmpty else { return library.availableRawEquipment }
        return library.availableRawEquipment.filter(preferred.contains)
    }

    private var equipmentFilterTitle: String {
        selectedEquipment.isEmpty ? "All \(availableEquipment.count)" : selectionTitle(selectedEquipment)
    }

    private var primaryFilterTitle: String {
        selectedPrimaryMuscles.isEmpty ? "All \(contextPrimaryMuscles.count)" : selectionTitle(selectedPrimaryMuscles)
    }

    private func selectionTitle(_ selection: Set<String>) -> String {
        if selection.isEmpty { return "All" }
        if selection.count == 1 { return selection.first ?? "All" }
        return "\(selection.count) selected"
    }

    private func filterMenu<Content: View>(
        title: String,
        value: String,
        systemImage: String,
        isActive: Bool,
        @ViewBuilder content: () -> Content
    ) -> some View {
        Menu(content: content) {
            WorkoutLogFilterPill(
                title: title,
                value: value,
                systemImage: systemImage,
                isActive: isActive
            )
        }
        .workoutPressable()
    }

    private func menuChoice(_ title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if isSelected {
                Label(title, systemImage: "checkmark")
            } else {
                Text(title)
            }
        }
    }

    private func muscleMenuChoice(
        _ title: String,
        muscles: Set<String>,
        isSelected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            if isSelected {
                Label(title, systemImage: "checkmark")
            } else {
                Label(title, image: MuscleGlyphAsset.name(title: title, muscles: muscles))
            }
        }
    }

    private func normalizePrimaryFilterSelection() {
        if hidesPrimaryFilter {
            selectedPrimaryMuscles.removeAll()
            return
        }
        let valid = Set(contextPrimaryMuscles)
        selectedPrimaryMuscles = singleStoredSelection(selectedPrimaryMuscles.intersection(valid))
    }

    private func normalizeEquipmentFilterSelection() {
        let valid = Set(availableEquipment)
        selectedEquipment = singleStoredSelection(selectedEquipment.intersection(valid))
    }

    private func singleStoredSelection(_ selection: Set<String>) -> Set<String> {
        guard let value = selection.sorted().first else { return [] }
        return [value]
    }

    private func resetFilters() {
        searchDebounceTask?.cancel()
        searchText = ""
        debouncedSearchText = ""
        selectedLevels.removeAll()
        selectedEquipment.removeAll()
        selectedPrimaryMuscles.removeAll()
        selectedSecondaryMuscles.removeAll()
        selectedForces.removeAll()
        selectedMechanics.removeAll()
        selectedCategories.removeAll()
        selectedSort = .name
        refreshDisplayExercises()
        scheduleFilterPersist()
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

private extension View {
    @ViewBuilder
    func keepsWorkoutLogToolbarDuringSearch() -> some View {
        if #available(iOS 17.1, *) {
            searchPresentationToolbarBehavior(.avoidHidingContent)
        } else {
            self
        }
    }
}

private struct WorkoutLogFilterPill: View {
    let title: String
    let value: String
    let systemImage: String
    let isActive: Bool

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: systemImage)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(isActive ? Color.workoutAccent : Color.workoutSecondaryAccent)
                .frame(width: 18)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Color.workoutMutedText)
                    .textCase(.uppercase)
                    .lineLimit(1)
                Text(value)
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
            }

            Image(systemName: "chevron.down")
                .font(.caption2.weight(.bold))
                .foregroundStyle(Color.workoutMutedText)
                .padding(.leading, 1)
        }
        .padding(.horizontal, 12)
        .frame(minWidth: 112, minHeight: 46, alignment: .leading)
        .background(
            Color.workoutPanel.opacity(isActive ? 0.46 : 0.30),
            in: RoundedRectangle(cornerRadius: 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(
                    (isActive ? Color.workoutAccent : Color.workoutHairline).opacity(isActive ? 0.42 : 0.30),
                    lineWidth: 0.5
                )
        }
        .contentShape(RoundedRectangle(cornerRadius: 17, style: .continuous))
    }
}

private struct WorkoutLogPickerRow: View {
    let item: ExerciseLibraryItem
    let isSelected: Bool
    let isSaved: Bool
    let action: () -> Void
    let toggleSaved: () -> Void
    let previewAction: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: action) {
                HStack(spacing: 12) {
                    AnimatedExerciseVisual(
                        exerciseName: item.name,
                        imagePaths: item.imagePaths,
                        height: 58,
                        fillsWidth: false,
                        allowsDerivedImageLookup: false,
                        animatesFrames: false
                    )
                    .frame(width: 76, height: 58)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .clipped()
                    .layoutPriority(0)
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 5) {
                        Text(item.name)
                            .font(.headline.weight(.semibold))
                            .foregroundStyle(Color.workoutCharcoal)
                            .lineLimit(2)

                        Text("\(item.primaryMusclesTitle) - \(item.rawEquipment)")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.workoutMutedText)
                            .lineLimit(1)
                            .minimumScaleFactor(0.72)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .layoutPriority(1)

                    Image(systemName: isSelected ? "checkmark.circle.fill" : "plus.circle.fill")
                        .font(.title3.weight(.bold))
                        .foregroundStyle(isSelected ? Color.workoutAccent : Color.workoutMutedText)
                        .frame(width: 34, height: 34)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(item.name), \(item.primaryMusclesTitle), \(item.rawEquipment)")
            .accessibilityValue(isSelected ? "Added" : "Not added")
            .accessibilityHint(isSelected ? "Double tap to remove from this day" : "Double tap to add to this day")

            Button(action: toggleSaved) {
                Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(isSaved ? Color.workoutAccent : Color.workoutMutedText.opacity(0.72))
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isSaved ? "Unsave exercise" : "Save exercise")
            .accessibilityHint(isSaved ? "Removes this exercise from Saved" : "Adds this exercise to Saved")

            Button(action: previewAction) {
                Image(systemName: "info.circle.fill")
                    .font(.system(size: 19, weight: .bold))
                    .foregroundStyle(Color.workoutAccent)
                    .frame(width: 34, height: 34)
                    .background(Color.workoutPanel.opacity(0.36), in: Circle())
                    .overlay {
                        Circle()
                            .stroke(Color.workoutHairline.opacity(0.28), lineWidth: 0.5)
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Preview \(item.name)")
        }
        .padding(.vertical, 4)
    }
}

private struct WorkoutLogPreviewActionBar: View {
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(
                isSelected ? "Remove from day" : "Add exercise",
                systemImage: isSelected ? "checkmark.circle.fill" : "plus.circle.fill"
            )
            .font(.headline.weight(.heavy))
            .foregroundStyle(isSelected ? Color.workoutCharcoal : Color.white)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(
                isSelected ? Color.workoutPanel.opacity(0.92) : Color.workoutAccent,
                in: Capsule()
            )
            .overlay {
                Capsule()
                    .stroke(
                        isSelected ? Color.workoutHairline.opacity(0.38) : Color.workoutAccent.opacity(0.55),
                        lineWidth: 0.7
                    )
            }
        }
        .buttonStyle(.plain)
        .workoutPressable()
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .flashPanel()
    }
}

// MARK: - Copy prior day

private struct WorkoutLogCopyDay: Identifiable {
    let date: Date
    let exercises: [StrengthPlannedExercise]

    var id: String { StrengthWorkoutStore.dateKey(for: date) }
}

private struct WorkoutLogExerciseHistorySheet: View {
    let request: WorkoutLogExerciseHistoryRequest
    let selectedDate: Date
    let weightUnit: WeightUnit
    let history: [StrengthExerciseLiftDay]
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if history.isEmpty {
                    ContentUnavailableView("No lift history yet", systemImage: "clock.arrow.circlepath")
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(history) { day in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(dayTitle(for: day.dateKey))
                                .font(.headline.weight(.semibold))
                                .foregroundStyle(Color.workoutCharcoal)
                            Text(StrengthExerciseLiftHistory.formatSummary(day.sets, displayUnit: weightUnit))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.workoutMutedText)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 4)
                        .listRowBackground(Color.workoutPanel.opacity(0.24))
                        .listRowSeparatorTint(Color.workoutHairline.opacity(0.28))
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .listStyle(.plain)
            .background(Color.workoutBackground)
            .navigationTitle(request.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onClose)
                        .font(.headline.weight(.heavy))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
    }

    private func dayTitle(for dateKey: String) -> String {
        guard let date = StrengthWorkoutStore.date(for: dateKey) else { return dateKey }
        if Calendar.current.isDateInToday(date) { return "Today" }
        if Calendar.current.isDateInYesterday(date) { return "Yesterday" }
        if Calendar.current.isDate(date, inSameDayAs: selectedDate) { return "Selected day" }
        return date.formatted(.dateTime.weekday(.wide).month(.abbreviated).day().year())
    }
}

private struct WorkoutLogCopySheet: View {
    let days: [WorkoutLogCopyDay]
    let targetTitle: String
    let onCopy: (Date, Bool) -> Void
    let onClose: () -> Void
    @State private var includeSetDetails = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("What to copy", selection: $includeSetDetails) {
                        Text("Without details").tag(false)
                        Text("With set details").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.workoutPanel.opacity(0.24))
                    Text(includeSetDetails
                         ? "Copies sets, weights, reps, RPE, units, and timers."
                         : "Adds exercise names only, with blank sets.")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.workoutMutedText)
                        .listRowBackground(Color.workoutPanel.opacity(0.24))
                } header: {
                    Text("What to copy")
                }
                if days.isEmpty {
                    ContentUnavailableView("No previous workouts", systemImage: "calendar.badge.exclamationmark")
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(days) { day in
                        Button {
                            onCopy(day.date, includeSetDetails)
                        } label: {
                            WorkoutLogCopyDayRow(day: day)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(Color.workoutPanel.opacity(0.24))
                        .listRowSeparatorTint(Color.workoutHairline.opacity(0.28))
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .listStyle(.plain)
            .background(Color.workoutBackground)
            .navigationTitle("Copy to \(targetTitle)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done", action: onClose)
                        .font(.headline.weight(.heavy))
                        .foregroundStyle(Color.workoutAccent)
                }
            }
        }
    }
}

private struct WorkoutLogCopyDayRow: View {
    let day: WorkoutLogCopyDay

    private var workoutNames: String {
        let names = day.exercises.prefix(3).map(\.name).joined(separator: ", ")
        let remaining = day.exercises.count - 3
        return remaining > 0 ? "\(names) + \(remaining)" : names
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: "calendar")
                .font(.headline.weight(.black))
                .foregroundStyle(Color.workoutAccent)
                .frame(width: 38, height: 38)
                .background(Color.workoutCard.opacity(0.60), in: Circle())
                .overlay {
                    Circle()
                        .stroke(Color.workoutHairline.opacity(0.34), lineWidth: 0.7)
                }

            VStack(alignment: .leading, spacing: 4) {
                Text(dayTitle)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.workoutCharcoal)
                    .lineLimit(1)
                Text(workoutNames)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.workoutMutedText)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            VStack(alignment: .trailing, spacing: 4) {
                Text("\(day.exercises.count)")
                    .font(.system(.title3, design: .rounded, weight: .black).monospacedDigit())
                    .foregroundStyle(Color.workoutAccent)
                Text(day.exercises.count == 1 ? "workout" : "workouts")
                    .font(.caption2.weight(.heavy))
                    .foregroundStyle(Color.workoutMutedText)
            }

            Image(systemName: "plus")
                .font(.caption.weight(.black))
                .foregroundStyle(Color.workoutOnAccent)
                .frame(width: 28, height: 28)
                .background(Color.workoutAccent, in: Circle())
        }
        .padding(.vertical, 4)
    }

    private var dayTitle: String {
        if Calendar.current.isDateInToday(day.date) { return "Today" }
        if Calendar.current.isDateInYesterday(day.date) { return "Yesterday" }
        return day.date.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }
}
