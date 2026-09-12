import Foundation
import SwiftUI

@Observable
final class StrengthWorkoutStore {
    struct PersistedState: Codable, Equatable {
        var version = 1
        var dayPlans: [String: StrengthWorkoutDayPlan] = [:]
        var completedSessions: [StrengthWorkoutSession] = []
        var savedExerciseIDs: Set<String> = []
        var preferences = StrengthWorkoutPreferences()
        // Optional so diaries saved before text/voice logging still decode.
        var customActivities: [StrengthPlannedExercise]?
        /// User-created strength exercises (device-local templates).
        var userExercises: [StrengthPlannedExercise]?
    }

    static let defaultStorageKey = "fudai.workouts.diary.state.v1"

    private(set) var dayPlans: [String: StrengthWorkoutDayPlan] = [:]
    private(set) var completedSessions: [StrengthWorkoutSession] = []
    private(set) var savedExerciseIDs: Set<String> = []
    private(set) var preferences = StrengthWorkoutPreferences()
    private(set) var customActivities: [StrengthPlannedExercise] = []
    private(set) var userExercises: [StrengthPlannedExercise] = []

    private var cachedExerciseLibrary: ExerciseLibraryService?
    private var exerciseLibraryFingerprint: Int = 0

    var exerciseLibrary: ExerciseLibraryService {
        let fingerprint = exerciseLibrarySourceFingerprint
        if let cachedExerciseLibrary, fingerprint == exerciseLibraryFingerprint {
            return cachedExerciseLibrary
        }

        let base = ExerciseLibraryService.shared.exercises
        let known = Set(base.map(\.id))
        let mergedCustom = (customActivities + userExercises)
            .filter { !known.contains($0.itemID) }
            .map(\.libraryItem)
        let merged = ExerciseLibraryService(exercises: base + mergedCustom)
        cachedExerciseLibrary = merged
        exerciseLibraryFingerprint = fingerprint
        return merged
    }

    private var exerciseLibrarySourceFingerprint: Int {
        var hasher = Hasher()
        hasher.combine(customActivities.map(\.itemID))
        hasher.combine(userExercises.map(\.itemID))
        return hasher.finalize()
    }
    var onWorkoutBurnUpserted: ((StrengthWorkoutSession) -> Void)?
    var onWorkoutBurnDeleted: ((UUID) -> Void)?

    private let defaults: UserDefaults
    private let storageKey: String
    private var liftSummaryCacheKey: String?
    private var liftSummaryCache: [String: String] = [:]

    init(defaults: UserDefaults = .standard, storageKey: String = StrengthWorkoutStore.defaultStorageKey) {
        self.defaults = defaults
        self.storageKey = storageKey
        load()
    }

    var sortedCompletedSessions: [StrengthWorkoutSession] {
        completedSessions.sorted {
            if $0.stableDiaryDateKey == $1.stableDiaryDateKey {
                return $0.completedAt > $1.completedAt
            }
            return $0.stableDiaryDateKey > $1.stableDiaryDateKey
        }
    }

    var workoutBurnSessions: [StrengthWorkoutSession] {
        sortedCompletedSessions.filter { $0.caloriesBurned != nil }
    }

    static func dateKey(for date: Date, calendar: Calendar = .current) -> String {
        StrengthWorkoutDate.key(for: date, calendar: calendar)
    }

    static func date(for key: String, calendar: Calendar = .current) -> Date? {
        StrengthWorkoutDate.date(for: key, calendar: calendar)
    }

    func plan(for date: Date) -> StrengthWorkoutDayPlan {
        let key = Self.dateKey(for: date)
        return dayPlans[key] ?? StrengthWorkoutDayPlan(dateKey: key)
    }

    func exercises(for date: Date) -> [StrengthPlannedExercise] {
        plan(for: date).exercises
    }

    func workoutCount(for date: Date) -> Int {
        exercises(for: date).count
    }

    func containsExercise(_ itemID: String, on date: Date) -> Bool {
        exercises(for: date).contains { $0.itemID == itemID }
    }

    /// Append the reviewed batch; stable draft IDs make retries idempotent.
    func addTextWorkout(_ draft: WorkoutTextDraft, library: [ExerciseLibraryItem]) throws {
        let additions = try draft.planned(library: library)
        guard let date = Self.date(for: draft.date) else { throw WorkoutTextError.invalid("Choose a valid date.") }
        let known = Set(customActivities.map(\.itemID))
        customActivities.append(contentsOf: additions.filter {
            $0.itemID.hasPrefix("custom_activity_") && !known.contains($0.itemID)
        }.map { item in
            var template = item
            template.sets = []
            template.timer = nil
            return template
        })
        updatePlan(for: date) { plan in
            let existing = Set(plan.exercises.map(\.id))
            plan.exercises.append(contentsOf: additions.filter { !existing.contains($0.id) })
        }
    }

    func toggleExercise(_ item: ExerciseLibraryItem, on date: Date) {
        updatePlan(for: date) { plan in
            if let index = plan.exercises.firstIndex(where: { $0.itemID == item.id }) {
                plan.exercises.remove(at: index)
            } else {
                plan.exercises.append(StrengthPlannedExercise(item: item))
            }
        }
    }

    @discardableResult
    func saveUserExercise(_ draft: UserExerciseDraft, existingItemID: String? = nil) -> ExerciseLibraryItem? {
        let trimmedName = draft.trimmedName
        guard !trimmedName.isEmpty else { return nil }

        let itemID = existingItemID ?? UserExercise.newID()
        var imagePaths = userExercises.first(where: { $0.itemID == itemID })?.imagePaths ?? []
        var orphanCandidates: [String] = []

        if draft.removePhoto {
            orphanCandidates.append(contentsOf: imagePaths)
            imagePaths = []
        }

        if let photoData = draft.photoData,
           let filename = FoodImageStore.shared.storeExercisePhoto(data: photoData) {
            orphanCandidates.append(contentsOf: imagePaths)
            imagePaths = [filename]
        }

        let item = draft.libraryItem(id: itemID, imagePaths: imagePaths)
        var template = StrengthPlannedExercise(item: item)
        template.sets = []
        template.timer = nil

        if let index = userExercises.firstIndex(where: { $0.itemID == itemID }) {
            userExercises[index] = template
        } else {
            userExercises.append(template)
        }
        save()
        orphanCandidates
            .filter { !referencesUserExerciseImage($0) }
            .forEach { FoodImageStore.shared.delete(filename: $0) }
        return item
    }

    func deleteUserExercise(itemID: String) {
        guard UserExercise.isUserExercise(itemID) else { return }
        let orphanCandidates = userExercises.first(where: { $0.itemID == itemID })?.imagePaths ?? []
        userExercises.removeAll { $0.itemID == itemID }
        savedExerciseIDs.remove(itemID)
        save()
        orphanCandidates
            .filter { !referencesUserExerciseImage($0) }
            .forEach { FoodImageStore.shared.delete(filename: $0) }
    }

    func userExerciseTemplate(for itemID: String) -> StrengthPlannedExercise? {
        userExercises.first { $0.itemID == itemID }
    }

    /// Appends a completed timed cardio entry for quick logging from Home.
    func logQuickCardio(
        _ item: ExerciseLibraryItem,
        minutes: Int,
        on date: Date,
        intensity: StrengthWorkoutIntensity = .moderate
    ) {
        guard minutes > 0 else { return }
        let seconds = Double(minutes * 60)
        var exercise = StrengthPlannedExercise(item: item)
        exercise.sets = []
        exercise.timer = StrengthExerciseTimer(
            accumulatedSeconds: seconds,
            savedDurationSeconds: seconds,
            intensity: intensity
        )
        updatePlan(for: date) { plan in
            plan.exercises.append(exercise)
        }
    }

    func removeExercise(_ exerciseID: UUID, on date: Date) {
        let removedPaths = exercises(for: date).first(where: { $0.id == exerciseID })?.imagePaths ?? []
        updatePlan(for: date) { plan in
            plan.exercises.removeAll { $0.id == exerciseID }
        }
        removedPaths
            .filter { !referencesUserExerciseImage($0) }
            .forEach { FoodImageStore.shared.delete(filename: $0) }
    }

    func setSetCount(_ count: Int, exerciseID: UUID, on date: Date) {
        updateExercise(exerciseID, on: date) { exercise in
            let target = min(max(count, 1), 12)
            if target > exercise.sets.count {
                let template = exercise.sets.last ?? StrengthPlannedSet()
                exercise.sets.append(contentsOf: (exercise.sets.count..<target).map { _ in
                    template.copyingFromPrevious()
                })
            } else if target < exercise.sets.count {
                exercise.sets.removeLast(exercise.sets.count - target)
            }
        }
    }

    func updateSet(
        exerciseID: UUID,
        setID: UUID,
        on date: Date,
        weight: String? = nil,
        weightUnit: WeightUnit? = nil,
        reps: String? = nil,
        rpe: String? = nil
    ) {
        updateExercise(exerciseID, on: date) { exercise in
            guard let setIndex = exercise.sets.firstIndex(where: { $0.id == setID }) else { return }
            if let weight {
                exercise.sets[setIndex].weight = Self.decimalText(weight)
                if let weightUnit { exercise.sets[setIndex].weightUnit = weightUnit.rawValue }
            }
            if let reps { exercise.sets[setIndex].reps = String(reps.filter(\.isNumber).prefix(4)) }
            if let rpe {
                exercise.sets[setIndex].rpe = preferences.rpeScale.sanitized(
                    rpe,
                    previousValue: exercise.sets[setIndex].rpe
                )
                exercise.sets[setIndex].rpeScale = preferences.rpeScale
            }
        }
    }

    func toggleSaved(_ itemID: String) {
        if savedExerciseIDs.contains(itemID) {
            savedExerciseIDs.remove(itemID)
        } else {
            savedExerciseIDs.insert(itemID)
        }
        save()
    }

    func updateTimer(
        _ action: StrengthExerciseTimerAction,
        exerciseID: UUID,
        on date: Date,
        now: Date = .now
    ) {
        updateExercise(exerciseID, on: date) { exercise in
            if case .discard = action {
                exercise.timer = nil
                return
            }
            var timer = exercise.timer ?? StrengthExerciseTimer()
            timer.apply(action, at: now)
            exercise.timer = timer
        }
    }

    func setTimerIntensity(
        _ intensity: StrengthWorkoutIntensity,
        exerciseID: UUID,
        on date: Date
    ) {
        updateExercise(exerciseID, on: date) { exercise in
            var timer = exercise.timer ?? StrengthExerciseTimer()
            timer.intensity = intensity
            exercise.timer = timer
        }
    }

    func copyPlan(from sourceDate: Date, to targetDate: Date, includeSetDetails: Bool = false) {
        let source = exercises(for: sourceDate)
        guard !source.isEmpty else { return }
        updatePlan(for: targetDate) { target in
            let existing = Set(target.exercises.map(\.itemID))
            target.exercises.append(contentsOf: source.filter { !existing.contains($0.itemID) }.map {
                $0.copiedForNewDay(includeSetDetails: includeSetDetails)
            })
        }
    }

    func previousPlanDates(before date: Date) -> [Date] {
        let selectedStart = Calendar.current.startOfDay(for: date)
        return dayPlans.values.compactMap { plan in
            guard !plan.exercises.isEmpty,
                  let planDate = Self.date(for: plan.dateKey),
                  Calendar.current.startOfDay(for: planDate) < selectedStart
            else { return nil }
            return planDate
        }
        .sorted(by: >)
    }

    func exerciseLiftHistory(
        itemID: String,
        name: String,
        before date: Date,
        limit: Int = 90
    ) -> [StrengthExerciseLiftDay] {
        let beforeKey = Self.dateKey(for: date)
        let priorKeys = Set(completedSessions.map(\.stableDiaryDateKey))
            .filter { $0 < beforeKey }
            .sorted(by: >)

        var results: [StrengthExerciseLiftDay] = []
        for key in priorKeys {
            guard results.count < limit else { break }
            let sets = liftSets(for: itemID, name: name, on: key)
            guard !sets.isEmpty else { continue }
            results.append(StrengthExerciseLiftDay(dateKey: key, sets: sets))
        }
        return results
    }

    func lastExerciseLiftSummary(
        itemID: String,
        name: String,
        before date: Date,
        displayUnit: WeightUnit
    ) -> String? {
        let beforeKey = Self.dateKey(for: date)
        let token = "\(beforeKey)|\(displayUnit.rawValue)|\(liftSummaryHistoryToken)"
        if liftSummaryCacheKey != token {
            liftSummaryCache = [:]
            liftSummaryCacheKey = token
        }
        let cacheKey = "\(itemID)\u{0}\(StrengthExerciseLiftHistory.normalizedName(name))"
        if let cached = liftSummaryCache[cacheKey] {
            return cached.isEmpty ? nil : cached
        }
        guard let latest = exerciseLiftHistory(itemID: itemID, name: name, before: date, limit: 1).first else {
            liftSummaryCache[cacheKey] = ""
            return nil
        }
        let summary = StrengthExerciseLiftHistory.formatSummary(latest.sets, displayUnit: displayUnit)
        liftSummaryCache[cacheKey] = summary
        return summary.isEmpty ? nil : summary
    }

    @discardableResult
    func completeWorkout(
        on date: Date,
        startedAt: Date,
        completedAt: Date = .now,
        elapsedSeconds: Int,
        weightUnit: WeightUnit
    ) -> StrengthWorkoutSession? {
        let planned = exercises(for: date)
        guard !planned.isEmpty else { return nil }

        let logs = completedExerciseLogs(from: planned, weightUnit: weightUnit)
        let session = StrengthWorkoutSession(
            diaryDate: Calendar.current.startOfDay(for: date),
            diaryDateKey: Self.dateKey(for: date),
            startedAt: startedAt,
            completedAt: completedAt,
            durationSeconds: max(1, elapsedSeconds),
            exercises: logs
        )
        completedSessions.append(session)
        save()
        return session
    }

    /// Snapshots the selected diary and stores one calculated burn record for
    /// that calendar day. Recalculating replaces the day in place and preserves
    /// its UUID so Apple Health can update rather than duplicate the sample.
    @discardableResult
    func upsertCalculatedWorkout(
        on date: Date,
        caloriesBurned: Int,
        weightUnit: WeightUnit,
        calculatedAt: Date = .now
    ) -> StrengthWorkoutSession? {
        let planned = exercises(for: date)
        let logs = completedExerciseLogs(from: planned, weightUnit: weightUnit)
        guard planned.contains(where: {
            $0.timer?.isSaved == true
                || (!$0.isCardio && $0.sets.contains { (Int($0.reps) ?? 0) > 0 })
        }) else { return nil }

        let key = Self.dateKey(for: date)
        let existingBurns = sortedCompletedSessions.filter {
            $0.stableDiaryDateKey == key && $0.caloriesBurned != nil
        }
        let existing = existingBurns.first
        let session = StrengthWorkoutSession(
            id: existing?.id ?? UUID(),
            diaryDate: Calendar.current.startOfDay(for: date),
            diaryDateKey: key,
            startedAt: calculatedAt,
            completedAt: calculatedAt,
            durationSeconds: 0,
            exercises: logs,
            caloriesBurned: min(max(caloriesBurned, 1), 5_000),
            healthSyncVersion: (existing?.healthSyncVersion ?? 0) + 1
        )

        // Keep timer-era completed sessions intact. The burn calculator owns
        // only the single daily burn snapshot it previously created.
        completedSessions.removeAll {
            $0.stableDiaryDateKey == key && $0.caloriesBurned != nil
        }
        completedSessions.append(session)
        save()
        for duplicate in existingBurns.dropFirst() where duplicate.id != session.id {
            onWorkoutBurnDeleted?(duplicate.id)
        }
        onWorkoutBurnUpserted?(session)
        return session
    }

    func caloriesBurned(on date: Date) -> Int? {
        let key = Self.dateKey(for: date)
        return sortedCompletedSessions.first {
            $0.stableDiaryDateKey == key && $0.caloriesBurned != nil
        }?.caloriesBurned
    }

    func latestSession(on date: Date) -> StrengthWorkoutSession? {
        let key = Self.dateKey(for: date)
        return sortedCompletedSessions.first { $0.stableDiaryDateKey == key }
    }

    func sessions(from start: Date, through end: Date) -> [StrengthWorkoutSession] {
        completedSessions
            .filter { $0.calendarDiaryDate >= start && $0.calendarDiaryDate <= end }
            .sorted {
                if $0.stableDiaryDateKey == $1.stableDiaryDateKey {
                    return $0.completedAt < $1.completedAt
                }
                return $0.stableDiaryDateKey < $1.stableDiaryDateKey
            }
    }

    func deleteSession(_ id: UUID) {
        let deletedBurnID = completedSessions.first {
            $0.id == id && $0.caloriesBurned != nil
        }?.id
        completedSessions.removeAll { $0.id == id }
        save()
        if let deletedBurnID { onWorkoutBurnDeleted?(deletedBurnID) }
    }

    /// Restores Fud AI-authored burn samples after a reinstall or new phone.
    /// This merge never fires write callbacks, so imported samples are not
    /// echoed back to Apple Health.
    func importWorkoutBurnSessions(_ imported: [StrengthWorkoutSession]) {
        guard !imported.isEmpty else { return }
        var changed = false

        for session in imported where session.caloriesBurned != nil {
            if let index = completedSessions.firstIndex(where: { $0.id == session.id }) {
                let localVersion = completedSessions[index].healthSyncVersion ?? 0
                let importedVersion = session.healthSyncVersion ?? 0
                if importedVersion > localVersion {
                    completedSessions[index] = mergedBurnSession(
                        local: completedSessions[index],
                        imported: session
                    )
                    changed = true
                }
                continue
            }

            if let sameDay = completedSessions.firstIndex(where: {
                $0.stableDiaryDateKey == session.stableDiaryDateKey && $0.caloriesBurned != nil
            }) {
                let localVersion = completedSessions[sameDay].healthSyncVersion ?? 0
                let importedVersion = session.healthSyncVersion ?? 0
                if importedVersion > localVersion {
                    completedSessions[sameDay] = mergedBurnSession(
                        local: completedSessions[sameDay],
                        imported: session
                    )
                    changed = true
                }
            } else {
                completedSessions.append(session)
                changed = true
            }
        }

        if changed { save() }
    }

    /// Apple Health stores the burn value and stable identity, not the diary's
    /// exercise snapshot. Preserve local exercise/set detail when a newer
    /// Health version is merged back into an existing record.
    private func mergedBurnSession(
        local: StrengthWorkoutSession,
        imported: StrengthWorkoutSession
    ) -> StrengthWorkoutSession {
        StrengthWorkoutSession(
            id: imported.id,
            diaryDate: imported.diaryDate,
            diaryDateKey: imported.diaryDateKey,
            startedAt: imported.startedAt,
            completedAt: imported.completedAt,
            durationSeconds: imported.exercises.isEmpty ? local.durationSeconds : imported.durationSeconds,
            exercises: imported.exercises.isEmpty ? local.exercises : imported.exercises,
            caloriesBurned: imported.caloriesBurned,
            healthSyncVersion: imported.healthSyncVersion
        )
    }

    func updatePreferences(_ mutate: (inout StrengthWorkoutPreferences) -> Void) {
        mutate(&preferences)
        preferences.sanitize()
        save()
    }

    func reloadFromDefaults() {
        dayPlans = [:]
        completedSessions = []
        savedExerciseIDs = []
        customActivities = []
        userExercises = []
        preferences = StrengthWorkoutPreferences()
        load()
    }

    func clearAll() {
        dayPlans = [:]
        completedSessions = []
        savedExerciseIDs = []
        customActivities = []
        userExercises.forEach { exercise in
            exercise.imagePaths.forEach { FoodImageStore.shared.delete(filename: $0) }
        }
        userExercises = []
        preferences = StrengthWorkoutPreferences()
        defaults.removeObject(forKey: storageKey)
    }

    private func updatePlan(for date: Date, mutate: (inout StrengthWorkoutDayPlan) -> Void) {
        let key = Self.dateKey(for: date)
        var plan = dayPlans[key] ?? StrengthWorkoutDayPlan(dateKey: key)
        let previousTimerInputs = savedTimerBurnInputs(in: plan)
        mutate(&plan)
        if plan.exercises.isEmpty {
            dayPlans.removeValue(forKey: key)
        } else {
            dayPlans[key] = plan
        }

        // The explicit Calculate action owns daily burn snapshots. Changing
        // their saved timer inputs invalidates both the local estimate and its
        // Health sample; otherwise discarded time would keep counting forever.
        let invalidatedBurnIDs: [UUID]
        if previousTimerInputs != savedTimerBurnInputs(in: plan) {
            invalidatedBurnIDs = completedSessions.filter {
                $0.stableDiaryDateKey == key && $0.caloriesBurned != nil
            }.map(\.id)
            completedSessions.removeAll {
                $0.stableDiaryDateKey == key && $0.caloriesBurned != nil
            }
        } else {
            invalidatedBurnIDs = []
        }
        save()
        for id in invalidatedBurnIDs { onWorkoutBurnDeleted?(id) }
    }

    private struct SavedTimerBurnInput: Equatable {
        let durationSeconds: Double
        let intensity: StrengthWorkoutIntensity
    }

    private func savedTimerBurnInputs(in plan: StrengthWorkoutDayPlan) -> [UUID: SavedTimerBurnInput] {
        plan.exercises.reduce(into: [:]) { inputs, exercise in
            guard let timer = exercise.timer,
                  !timer.isRunning,
                  timer.isSaved,
                  let duration = timer.savedDurationSeconds
            else { return }
            inputs[exercise.id] = SavedTimerBurnInput(durationSeconds: duration, intensity: StrengthWorkoutBurnEstimator.timerIntensity(for: exercise, defaultRPEScale: preferences.rpeScale))
        }
    }

    private func updateExercise(_ exerciseID: UUID, on date: Date, mutate: (inout StrengthPlannedExercise) -> Void) {
        updatePlan(for: date) { plan in
            guard let index = plan.exercises.firstIndex(where: { $0.id == exerciseID }) else { return }
            mutate(&plan.exercises[index])
        }
    }

    private func liftSets(for itemID: String, name: String, on dateKey: String) -> [StrengthExerciseLiftSet] {
        guard let session = preferredHistorySession(on: dateKey),
              let exercise = session.exercises.first(where: {
                  StrengthExerciseLiftHistory.matches(
                      itemID: itemID,
                      name: name,
                      candidateItemID: $0.itemID,
                      candidateName: $0.name
                  )
              })
        else { return [] }
        return StrengthExerciseLiftHistory.performedSets(from: exercise.sets)
    }

    private func preferredHistorySession(on dateKey: String) -> StrengthWorkoutSession? {
        let sessions = completedSessions.filter { $0.stableDiaryDateKey == dateKey }
        guard !sessions.isEmpty else { return nil }
        let burns = sessions.filter { $0.caloriesBurned != nil }
        if let latestBurn = burns.max(by: {
            let left = $0.healthSyncVersion ?? 0
            let right = $1.healthSyncVersion ?? 0
            if left == right { return $0.completedAt < $1.completedAt }
            return left < right
        }) {
            return latestBurn
        }
        return sessions.max(by: { $0.completedAt < $1.completedAt })
    }

    private func referencesUserExerciseImage(_ filename: String) -> Bool {
        if userExercises.contains(where: { $0.imagePaths.contains(filename) }) { return true }
        if customActivities.contains(where: { $0.imagePaths.contains(filename) }) { return true }
        return dayPlans.values.contains { plan in
            plan.exercises.contains { $0.imagePaths.contains(filename) }
        }
    }

    private func completedExerciseLogs(
        from planned: [StrengthPlannedExercise],
        weightUnit: WeightUnit
    ) -> [StrengthCompletedExercise] {
        planned.map { exercise in
            StrengthCompletedExercise(
                itemID: exercise.itemID,
                name: exercise.name,
                targetMuscles: exercise.primaryMuscles,
                equipment: exercise.rawEquipment,
                sets: exercise.sets.enumerated().map { index, set in
                    StrengthCompletedSet(
                        setNumber: index + 1,
                        weight: set.weight.trimmingCharacters(in: .whitespacesAndNewlines),
                        weightUnit: set.weightUnit ?? weightUnit.rawValue,
                        reps: set.reps.trimmingCharacters(in: .whitespacesAndNewlines),
                        rpe: set.rpe.trimmingCharacters(in: .whitespacesAndNewlines),
                        rpeScale: set.rpeScale ?? preferences.rpeScale
                    )
                },
                durationSeconds: exercise.timer?.isSaved == true ? exercise.timer?.savedDurationSeconds : nil,
                intensity: exercise.timer?.isSaved == true ? StrengthWorkoutBurnEstimator.timerIntensity(for: exercise, defaultRPEScale: preferences.rpeScale) : nil
            )
        }
    }

    private func load() {
        guard let data = defaults.data(forKey: storageKey),
              let state = try? JSONDecoder().decode(PersistedState.self, from: data),
              state.version == 1
        else { return }
        dayPlans = state.dayPlans
        completedSessions = state.completedSessions
        savedExerciseIDs = state.savedExerciseIDs
        customActivities = state.customActivities ?? []
        userExercises = state.userExercises ?? []
        preferences = state.preferences
        preferences.sanitize()
    }

    private var liftSummaryHistoryToken: Int {
        completedSessions.reduce(into: 0) { token, session in
            token = 31 &* token &+ session.stableDiaryDateKey.hashValue
            token = 31 &* token &+ session.completedAt.hashValue
            token = 31 &* token &+ (session.healthSyncVersion ?? 0)
            for exercise in session.exercises {
                token = 31 &* token &+ exercise.itemID.hashValue
                token = 31 &* token &+ exercise.sets.filter(\.isPerformed).count
            }
        }
    }

    private func save() {
        let state = PersistedState(
            dayPlans: dayPlans,
            completedSessions: completedSessions,
            savedExerciseIDs: savedExerciseIDs,
            preferences: preferences,
            customActivities: customActivities,
            userExercises: userExercises
        )
        guard let data = try? JSONEncoder().encode(state) else { return }
        defaults.set(data, forKey: storageKey)
    }

    private static func decimalText(_ value: String) -> String {
        var output = ""
        var hasDecimal = false
        for character in value.replacingOccurrences(of: ",", with: ".") {
            if character.isNumber {
                output.append(character)
            } else if character == ".", !hasDecimal {
                hasDecimal = true
                output.append(character)
            }
            if output.count >= 7 { break }
        }
        return output
    }
}
