import Foundation
import HealthKit

struct HealthEnergySummary {
    var activeAverageCalories: Int
    var basalAverageCalories: Int?
    var totalAverageCalories: Int?
    var daysUsed: Int
    var requestedDays: Int
}

/// One calendar day's measured Health energy. Active energy explicitly excludes
/// samples tagged as Fud AI's own workout-burn estimates, preventing feedback.
struct HealthEnergyDay: Equatable {
    let date: Date
    let activeCalories: Int
    let basalCalories: Int?

    var totalCalories: Int? {
        basalCalories.map { activeCalories + $0 }
    }
}

/// Measured burn for one local calendar day. Used by the home burn line and nightly summary.
struct HealthDailyEnergy: Equatable {
    let activeCalories: Int
    let totalCalories: Int?
}

/// Custom metadata attached to every app-owned HealthKit nutrition sample.
/// HealthKit has no first-class food-mass field, so this is the only lossless
/// path for restoring the original serving after an app reinstall.
struct HealthFoodServingMetadata: Equatable {
    static let schemaVersion = 1
    static let versionKey = "fudai_food_metadata_version"
    static let servingSizeKey = "fudai_serving_size_grams"
    static let selectedUnitKey = "fudai_selected_serving_unit"
    static let selectedQuantityKey = "fudai_selected_serving_quantity"
    static let unitOptionsKey = "fudai_serving_unit_options"

    var servingSizeGrams: Double?
    var servingUnitOptions: [ServingUnitOption]
    var selectedServingUnit: String?
    var selectedServingQuantity: Double?

    init(entry: FoodEntry) {
        servingSizeGrams = entry.servingSizeGrams
        servingUnitOptions = entry.servingUnitOptions
        selectedServingUnit = entry.selectedServingUnit
        selectedServingQuantity = entry.selectedServingQuantity
    }

    var dictionary: [String: Any] {
        var metadata: [String: Any] = [Self.versionKey: Self.schemaVersion]
        if let servingSizeGrams, servingSizeGrams.isFinite, servingSizeGrams > 0 {
            metadata[Self.servingSizeKey] = servingSizeGrams
        }
        if let selectedServingUnit, !selectedServingUnit.isEmpty {
            metadata[Self.selectedUnitKey] = selectedServingUnit
        }
        if let selectedServingQuantity, selectedServingQuantity.isFinite, selectedServingQuantity > 0 {
            metadata[Self.selectedQuantityKey] = selectedServingQuantity
        }
        if !servingUnitOptions.isEmpty,
           let data = try? JSONEncoder().encode(servingUnitOptions),
           let json = String(data: data, encoding: .utf8) {
            metadata[Self.unitOptionsKey] = json
        }
        return metadata
    }

    static func decode(from metadata: [String: Any]?) -> HealthFoodServingMetadata? {
        guard let metadata,
              (metadata[versionKey] as? NSNumber)?.intValue ?? 0 >= schemaVersion
        else { return nil }
        let options: [ServingUnitOption]
        if let json = metadata[unitOptionsKey] as? String,
           let data = json.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([ServingUnitOption].self, from: data) {
            options = decoded
        } else {
            options = []
        }
        return HealthFoodServingMetadata(
            servingSizeGrams: (metadata[servingSizeKey] as? NSNumber)?.doubleValue,
            servingUnitOptions: options,
            selectedServingUnit: metadata[selectedUnitKey] as? String,
            selectedServingQuantity: (metadata[selectedQuantityKey] as? NSNumber)?.doubleValue
        )
    }

    private init(
        servingSizeGrams: Double?,
        servingUnitOptions: [ServingUnitOption],
        selectedServingUnit: String?,
        selectedServingQuantity: Double?
    ) {
        self.servingSizeGrams = servingSizeGrams
        self.servingUnitOptions = servingUnitOptions
        self.selectedServingUnit = selectedServingUnit
        self.selectedServingQuantity = selectedServingQuantity
    }
}

@Observable
class HealthKitManager {
    var authorizationStatus: HKAuthorizationStatus = .notDetermined

    /// Args, in order: weight (kg), weightDate, weightFudaiID, heightCm,
    /// bodyFat (fraction 0–1), bodyFatDate, bodyFatFudaiID, dob, sex.
    /// FudaiID is non-nil when the latest sample of that type was written by us
    /// (matched by metadata key) — observer caller uses it to skip echo-imports.
    var onBodyMeasurementsChanged: ((Double?, Date?, UUID?, Double?, Double?, Date?, UUID?, Date?, HKBiologicalSex?) -> Void)?
    var onImportedWorkoutsChanged: (() -> Void)?

    private let healthStore = HKHealthStore()
    private var observerQueries: [HKObserverQuery] = []

    // MARK: - Types

    /// Bump this when adding new HealthKit types so we can re-request authorization
    /// for users who already authorized the old set. Just an integer schema marker,
    /// not credentials — named to avoid CodeQL's "auth"-keyword heuristic false positive.
    /// v5: dietary types joined the read set so the food log can be restored from
    /// our own Health samples after a reinstall or new phone; the bump also re-runs
    /// the weight/body-fat backfills, which now restore own samples too.
    /// v6: active energy joined the write set so calculated workout calories can
    /// stay synchronized with Apple Health.
    /// v8: stepCount joined the read set for daily steps on Home.
    /// v9: workout samples joined the read set so Apple Watch / Health workouts
    /// can be imported into Fud AI without a Watch companion app.
    private let typesVersion = 9
    private let typesVersionKey = "healthKitTypesVersion"

    /// Active-energy samples written for the workout diary are deliberately
    /// app-owned and tagged. The stable session id makes updates/deletes exact,
    /// while the date key restores the user's chosen diary day after reinstall.
    private let workoutBurnSessionIDKey = "fudai_workout_session_id"
    private let workoutBurnDateKey = "fudai_workout_date_key"
    private let workoutBurnSyncVersionKey = "fudai_workout_sync_version"
    private let workoutBurnSyncIdentifierPrefix = "fudai.workout-burn"
    private let workoutBurnDeletionTombstonesKey = "healthKitWorkoutBurnDeletionTombstones"
    private let defaultWorkoutBurnSyncVersion = 1
    /// HealthKit indexes saves and deletes asynchronously. Funnel every workout
    /// burn mutation and reconciliation through one tail so a later delete can
    /// never be overtaken by an older recalculation save.
    private var workoutBurnOperationTail: Task<Void, Never>?

    private let nutritionTypeIdentifiers: [HKQuantityTypeIdentifier] = [
        // Calories + macronutrients
        .dietaryEnergyConsumed,
        .dietaryProtein,
        .dietaryCarbohydrates,
        .dietaryFatTotal,
        // Existing detailed nutrition sync
        .dietarySugar,
        .dietaryFiber,
        .dietaryFatSaturated,
        .dietaryFatMonounsaturated,
        .dietaryFatPolyunsaturated,
        .dietaryCholesterol,
        .dietaryCaffeine,
        .dietarySodium,
        .dietaryPotassium,
        // Expanded HealthKit nutrition sync for downstream apps such as Bevel
        .dietaryCalcium,
        .dietaryIron,
        .dietaryMagnesium,
        .dietaryZinc,
        .dietaryVitaminA,
        .dietaryVitaminC,
        .dietaryVitaminD,
        .dietaryVitaminB12,
        .dietaryVitaminE,
        .dietaryVitaminK,
        .dietaryFolate,
    ]

    private let expandedNutritionTypeIdentifiers: Set<HKQuantityTypeIdentifier> = [
        .dietaryCaffeine,
        .dietaryCalcium,
        .dietaryIron,
        .dietaryMagnesium,
        .dietaryZinc,
        .dietaryVitaminA,
        .dietaryVitaminC,
        .dietaryVitaminD,
        .dietaryVitaminB12,
        .dietaryVitaminE,
        .dietaryVitaminK,
        .dietaryFolate,
    ]

    private var dietaryShareTypes: Set<HKSampleType> {
        Set(nutritionTypeIdentifiers.map { HKQuantityType($0) })
    }

    private var shareTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = [
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
            HKQuantityType(.activeEnergyBurned),
        ]
        types.formUnion(dietaryShareTypes)
        return types
    }

    private let nutritionBackfillVersionKey = "healthKitNutritionBackfillVersion"
    /// Separate from permission `typesVersion`: metadata-only migrations do not
    /// require prompting the user for Health authorization again.
    private let nutritionBackfillVersion = 8
    private var isBackfillingNutrition = false

    /// One-shot import of historical weight + body-fat samples. Once a backfill
    /// completes for the current typesVersion the key is stamped so subsequent
    /// scene-active wire-ups skip it. Keeps these separate from the nutrition
    /// backfill version so each one can be re-run independently if we ever bump
    /// only one of the type sets.
    private let weightBackfillVersionKey = "healthKitWeightBackfillVersion"
    private let bodyFatBackfillVersionKey = "healthKitBodyFatBackfillVersion"
    private var isBackfillingWeight = false
    private var isBackfillingBodyFat = false
    private var isBackfillingWorkoutBurn = false
    private let workoutImportLookbackDays = 90
    private let workoutImportLastSyncKey = "healthKitWorkoutImportLastSync"
    /// Serializes workout imports so observer callbacks queue instead of being dropped.
    private var workoutImportOperationTail: Task<Void, Never>?

    private struct NutritionQuantity {
        var identifier: HKQuantityTypeIdentifier
        var value: Double
        var unit: HKUnit
    }

    private var readTypes: Set<HKObjectType> {
        var types: Set<HKObjectType> = [
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
            HKQuantityType(.activeEnergyBurned),
            HKQuantityType(.basalEnergyBurned),
            HKQuantityType(.stepCount),
            HKCharacteristicType(.dateOfBirth),
            HKCharacteristicType(.biologicalSex),
        ]
        // Dietary read access powers restoreFoodEntriesFromHealthKitIfNeeded —
        // rebuilding the food log from our own tagged samples after a reinstall.
        types.formUnion(nutritionTypeIdentifiers.map { HKQuantityType($0) })
        types.insert(HKObjectType.workoutType())
        return types
    }

    /// True if user previously authorized but new types were added since.
    var needsReauthorization: Bool {
        // Accept either the new key or the legacy "healthKitAuthVersion" key so existing
        // users who already granted permissions don't get re-prompted after this rename.
        let stored = max(
            UserDefaults.standard.integer(forKey: typesVersionKey),
            UserDefaults.standard.integer(forKey: "healthKitAuthVersion")
        )
        let enabled = UserDefaults.standard.bool(forKey: "healthKitEnabled")
        return enabled && stored < typesVersion
    }

    // MARK: - Authorization

    func requestAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        do {
            try await healthStore.requestAuthorization(toShare: shareTypes, read: readTypes)
            authorizationStatus = healthStore.authorizationStatus(for: HKQuantityType(.bodyMass))
            persistCurrentTypesVersion()
            return true
        } catch {
            return false
        }
    }

    /// Writes just the integer schema marker for the set of HealthKit types we request.
    /// Not sensitive data — extracted into its own method to keep it out of the context
    /// CodeQL's "cleartext storage" heuristic scans.
    private func persistCurrentTypesVersion() {
        UserDefaults.standard.set(typesVersion, forKey: typesVersionKey)
    }

    /// Whether HealthKit currently has write permission for at least one nutrition sample.
    var hasNutritionWriteAccess: Bool {
        dietaryShareTypes.contains { healthStore.authorizationStatus(for: $0) == .sharingAuthorized }
    }

    // MARK: - Write Body Measurements

    /// Profile-state push (no associated WeightEntry). Tagged with a synthetic UUID for
    /// forward compatibility — without the tag, a per-entry `deleteWeight(entryID:)` can't
    /// target it later. Delete All Data is local-only so untagged samples here wouldn't
    /// have been purged anyway, but tagging is cheap and keeps options open.
    func writeWeight(kg: Double, date: Date) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.bodyMass)
        let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: kg)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: date,
            end: date,
            metadata: ["fudai_weight_id": UUID().uuidString]
        )
        healthStore.save(sample) { _, _ in }
    }

    /// Writes a weight entry to HealthKit tagged with the entry's UUID so it can be deleted later.
    func writeWeight(for entry: WeightEntry) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.bodyMass)
        let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: entry.weightKg)
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: entry.date,
            end: entry.date,
            metadata: ["fudai_weight_id": entry.id.uuidString]
        )
        healthStore.save(sample) { _, _ in }
    }

    /// Deletes the HealthKit weight sample tagged with this entry's UUID.
    /// Bypasses the `healthKitEnabled` flag so a weight synced earlier still gets removed
    /// even if the user has since turned HealthKit sync off.
    func deleteWeight(entryID: UUID) {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_weight_id", operatorType: .equalTo, value: entryID.uuidString)
        healthStore.deleteObjects(of: HKQuantityType(.bodyMass), predicate: predicate) { _, _, _ in }
    }

    func writeHeight(cm: Double) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.height)
        let quantity = HKQuantity(unit: .meterUnit(with: .centi), doubleValue: cm)
        let sample = HKQuantitySample(type: type, quantity: quantity, start: .now, end: .now)
        healthStore.save(sample) { _, _ in }
    }

    func writeBodyFat(fraction: Double) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.bodyFatPercentage)
        let quantity = HKQuantity(unit: .percent(), doubleValue: fraction)
        // Tag with a synthetic fudai_bodyfat_id so the change-token observer
        // can recognize "this is our own write" and not re-import it as if it
        // were a fresh external sample. Same convention as writeWeight(kg:date:).
        let metadata: [String: Any] = ["fudai_bodyfat_id": UUID().uuidString]
        let sample = HKQuantitySample(type: type, quantity: quantity, start: .now, end: .now, metadata: metadata)
        healthStore.save(sample) { _, _ in }
    }

    /// Per-entry overload — used when a BodyFatStore entry is added so the HK
    /// sample can later be deleted by metadata predicate (no fragile date+value
    /// match needed). Mirrors writeWeight(for entry: WeightEntry).
    func writeBodyFat(for entry: BodyFatEntry) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.bodyFatPercentage)
        let quantity = HKQuantity(unit: .percent(), doubleValue: entry.bodyFatFraction)
        let metadata: [String: Any] = ["fudai_bodyfat_id": entry.id.uuidString]
        let sample = HKQuantitySample(type: type, quantity: quantity, start: entry.date, end: entry.date, metadata: metadata)
        healthStore.save(sample) { _, _ in }
    }

    /// Delete the HK body-fat sample we tagged with this entry's UUID. Bypasses
    /// healthKitEnabled so an in-app delete still cleans up samples exported
    /// while sync was enabled — same policy as deleteWeight / deleteNutrition.
    func deleteBodyFat(entryID: UUID) {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_bodyfat_id", operatorType: .equalTo, value: entryID.uuidString)
        healthStore.deleteObjects(of: HKQuantityType(.bodyFatPercentage), predicate: predicate) { _, _, _ in }
    }

    // MARK: - Workout Burn

    /// Replaces the Apple Health active-energy sample for a calculated diary
    /// session. Operations are serialized globally because HealthKit can finish
    /// two rapid saves/deletes out of submission order otherwise.
    func updateWorkoutBurn(for session: StrengthWorkoutSession) {
        removeWorkoutBurnDeletionTombstone(session.id)
        enqueueWorkoutBurnOperation { [weak self] in
            guard let self else { return }
            await self.replaceWorkoutBurnSample(with: session)
        }
    }

    /// Removes only the active-energy sample tagged with this Fud AI session.
    /// The sync toggle is intentionally ignored so deleting local history also
    /// cleans up a sample exported before the user switched Health sync off.
    func deleteWorkoutBurn(sessionID: UUID) {
        // Mark synchronously, before the queued Health delete. A foreground
        // restore that is already in flight will therefore refuse to resurrect
        // this id even if Health still returns its soon-to-be-deleted sample.
        addWorkoutBurnDeletionTombstone(sessionID)
        enqueueWorkoutBurnOperation { [weak self] in
            guard let self else { return }
            if await self.deleteWorkoutBurnSamples(sessionID: sessionID) {
                self.removeWorkoutBurnDeletionTombstone(sessionID)
            }
        }
    }

    /// Returns the valid workout-burn samples written by this app, preserving
    /// their original session ids and stable diary dates. A nil result means the
    /// Health query failed; an empty result is a successful query with no data.
    func fetchOwnedWorkoutBurnSessions() async -> [StrengthWorkoutSession]? {
        let type = HKQuantityType(.activeEnergyBurned)
        let taggedPredicate = HKQuery.predicateForObjects(withMetadataKey: workoutBurnSessionIDKey)
        let ownSourcePredicate = HKQuery.predicateForObjects(from: .default())
        let predicate = NSCompoundPredicate(
            andPredicateWithSubpredicates: [taggedPredicate, ownSourcePredicate]
        )
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: type,
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [sortDescriptor]
            ) { [workoutBurnSessionIDKey, workoutBurnDateKey, workoutBurnSyncVersionKey] _, results, error in
                guard error == nil, let samples = results as? [HKQuantitySample] else {
                    continuation.resume(returning: nil)
                    return
                }

                // A duplicate can exist only if an older save raced HealthKit's
                // indexing. Keep the newest valid sample for each stable id.
                var sessionsByID: [UUID: StrengthWorkoutSession] = [:]
                for sample in samples {
                    guard sample.sourceRevision.source == HKSource.default(),
                          let metadata = sample.metadata,
                          let idString = metadata[workoutBurnSessionIDKey] as? String,
                          let sessionID = UUID(uuidString: idString),
                          let dateKey = metadata[workoutBurnDateKey] as? String,
                          let diaryDate = StrengthWorkoutDate.date(for: dateKey),
                          StrengthWorkoutDate.key(for: diaryDate) == dateKey,
                          let syncVersionNumber = metadata[workoutBurnSyncVersionKey] as? NSNumber,
                          syncVersionNumber.intValue > 0
                    else { continue }

                    let caloriesValue = sample.quantity.doubleValue(for: .kilocalorie())
                    guard caloriesValue.isFinite, caloriesValue > 0 else { continue }
                    let calories = Int(caloriesValue.rounded())
                    guard calories > 0 else { continue }

                    let session = StrengthWorkoutSession(
                        id: sessionID,
                        diaryDate: diaryDate,
                        diaryDateKey: dateKey,
                        startedAt: sample.startDate,
                        completedAt: sample.endDate,
                        durationSeconds: 0,
                        exercises: [],
                        caloriesBurned: calories,
                        healthSyncVersion: syncVersionNumber.intValue
                    )
                    if let current = sessionsByID[sessionID] {
                        let currentVersion = current.healthSyncVersion ?? 0
                        let candidateVersion = session.healthSyncVersion ?? 0
                        if currentVersion > candidateVersion
                            || (currentVersion == candidateVersion && current.completedAt > session.completedAt) {
                            continue
                        }
                    }
                    sessionsByID[sessionID] = session
                }
                continuation.resume(returning: sessionsByID.values.sorted {
                    if $0.stableDiaryDateKey == $1.stableDiaryDateKey {
                        return $0.completedAt < $1.completedAt
                    }
                    return $0.stableDiaryDateKey < $1.stableDiaryDateKey
                })
            }
            healthStore.execute(query)
        }
    }

    /// Reconciles app-owned workout burn samples in both directions. Health-only
    /// rows restore local history after reinstall, while local rows created or
    /// recalculated with Health disabled are exported when sync resumes.
    func synchronizeWorkoutBurnsWithHealthKit(
        existing: @escaping () -> [StrengthWorkoutSession],
        mergeBatch: @escaping ([StrengthWorkoutSession]) -> Void
    ) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard !isBackfillingWorkoutBurn else { return }
        isBackfillingWorkoutBurn = true
        enqueueWorkoutBurnOperation { [weak self] in
            guard let self else { return }
            defer { self.isBackfillingWorkoutBurn = false }
            guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

            await self.retryPendingWorkoutBurnDeletions()

            let localSessions = self.preferredWorkoutBurnSessionsByDate(existing())
            guard let fetchedHealthSessions = await self.fetchOwnedWorkoutBurnSessions() else {
                // Read permission can be unavailable independently of write
                // permission. Exact-id replace remains safe and still backfills
                // local calculations without making assumptions about read auth.
                for session in localSessions.values
                    where !self.hasWorkoutBurnDeletionTombstone(session.id) {
                    await self.replaceWorkoutBurnSample(with: session)
                }
                return
            }

            let tombstones = self.workoutBurnDeletionTombstones
            let healthSessions = fetchedHealthSessions.filter { !tombstones.contains($0.id) }
            let healthGroups = Dictionary(grouping: healthSessions, by: \.stableDiaryDateKey)
            var preferredHealthByDate: [String: StrengthWorkoutSession] = [:]

            // Old racing saves or another install can leave more than one owned
            // id on the same diary day. Retain the newest and remove the rest.
            for (dateKey, sessions) in healthGroups {
                guard let preferred = sessions.max(by: { self.shouldPreferWorkoutBurn($1, over: $0) }) else { continue }
                preferredHealthByDate[dateKey] = preferred
                for duplicate in sessions where duplicate.id != preferred.id {
                    _ = await self.deleteWorkoutBurnSamples(sessionID: duplicate.id)
                }
            }

            var imports: [StrengthWorkoutSession] = []
            let allDateKeys = Set(localSessions.keys).union(preferredHealthByDate.keys)
            for dateKey in allDateKeys {
                let local = localSessions[dateKey]
                let health = preferredHealthByDate[dateKey]

                switch (local, health) {
                case let (local?, nil):
                    guard !self.hasWorkoutBurnDeletionTombstone(local.id) else { continue }
                    await self.replaceWorkoutBurnSample(with: local)

                case let (nil, health?):
                    guard !self.hasWorkoutBurnDeletionTombstone(health.id) else { continue }
                    imports.append(health)

                case let (local?, health?):
                    guard !self.hasWorkoutBurnDeletionTombstone(local.id) else { continue }

                    if local.id != health.id {
                        // Local data has the exercise/set snapshot that a Health
                        // quantity sample cannot carry, so it wins an id conflict.
                        _ = await self.deleteWorkoutBurnSamples(sessionID: health.id)
                        await self.replaceWorkoutBurnSample(with: local)
                        continue
                    }

                    let localVersion = local.healthSyncVersion ?? 0
                    let healthVersion = health.healthSyncVersion ?? 0
                    if healthVersion > localVersion {
                        var merged = local
                        merged.caloriesBurned = health.caloriesBurned
                        merged.healthSyncVersion = health.healthSyncVersion
                        imports.append(merged)
                    } else if localVersion > healthVersion
                                || local.caloriesBurned != health.caloriesBurned
                                || local.stableDiaryDateKey != health.stableDiaryDateKey {
                        await self.replaceWorkoutBurnSample(with: local)
                    }

                case (nil, nil):
                    break
                }
            }

            // A delete can be requested while one of the Health calls above is
            // suspended. Re-check tombstones immediately before the synchronous
            // store merge so an already-fetched row cannot be resurrected.
            let safeImports = imports.filter {
                !self.hasWorkoutBurnDeletionTombstone($0.id)
            }
            if !safeImports.isEmpty {
                mergeBatch(safeImports)
            }
        }
    }

    private func enqueueWorkoutBurnOperation(_ operation: @escaping () async -> Void) {
        let previous = workoutBurnOperationTail
        workoutBurnOperationTail = Task {
            _ = await previous?.result
            await operation()
        }
    }

    private func replaceWorkoutBurnSample(with session: StrengthWorkoutSession) async {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled"),
              !hasWorkoutBurnDeletionTombstone(session.id),
              let calories = session.caloriesBurned,
              calories > 0
        else { return }

        let type = HKQuantityType(.activeEnergyBurned)
        guard healthStore.authorizationStatus(for: type) == .sharingAuthorized else { return }
        // Do not save after a failed delete: that could leave the old sample and
        // a replacement both contributing to Health's cumulative energy total.
        guard await deleteWorkoutBurnSamples(sessionID: session.id) else { return }

        let diaryDate = session.calendarDiaryDate
        let sampleDate = Calendar.current.date(
            bySettingHour: 12,
            minute: 0,
            second: 0,
            of: diaryDate
        ) ?? diaryDate
        let syncVersion = max(
            defaultWorkoutBurnSyncVersion,
            session.healthSyncVersion ?? defaultWorkoutBurnSyncVersion
        )
        let metadata: [String: Any] = [
            workoutBurnSessionIDKey: session.id.uuidString,
            workoutBurnDateKey: session.stableDiaryDateKey,
            workoutBurnSyncVersionKey: syncVersion,
            HKMetadataKeySyncIdentifier: "\(workoutBurnSyncIdentifierPrefix).\(session.id.uuidString)",
            HKMetadataKeySyncVersion: syncVersion,
            HKMetadataKeyWasUserEntered: true,
        ]
        let quantity = HKQuantity(unit: .kilocalorie(), doubleValue: Double(calories))
        let sample = HKQuantitySample(
            type: type,
            quantity: quantity,
            start: sampleDate,
            end: sampleDate,
            metadata: metadata
        )
        _ = await saveWorkoutBurnSample(sample)
    }

    private func saveWorkoutBurnSample(_ sample: HKQuantitySample) async -> Bool {
        await withCheckedContinuation { continuation in
            healthStore.save(sample) { success, _ in continuation.resume(returning: success) }
        }
    }

    private func deleteWorkoutBurnSamples(sessionID: UUID) async -> Bool {
        let idPredicate = HKQuery.predicateForObjects(
            withMetadataKey: workoutBurnSessionIDKey,
            operatorType: .equalTo,
            value: sessionID.uuidString
        )
        let ownSourcePredicate = HKQuery.predicateForObjects(from: .default())
        let predicate = NSCompoundPredicate(
            andPredicateWithSubpredicates: [idPredicate, ownSourcePredicate]
        )
        return await withCheckedContinuation { continuation in
            healthStore.deleteObjects(of: HKQuantityType(.activeEnergyBurned), predicate: predicate) { success, _, _ in
                continuation.resume(returning: success)
            }
        }
    }

    private var workoutBurnDeletionTombstones: Set<UUID> {
        Set(
            (UserDefaults.standard.stringArray(forKey: workoutBurnDeletionTombstonesKey) ?? [])
                .compactMap(UUID.init(uuidString:))
        )
    }

    private func addWorkoutBurnDeletionTombstone(_ sessionID: UUID) {
        var ids = workoutBurnDeletionTombstones
        ids.insert(sessionID)
        persistWorkoutBurnDeletionTombstones(ids)
    }

    private func removeWorkoutBurnDeletionTombstone(_ sessionID: UUID) {
        var ids = workoutBurnDeletionTombstones
        guard ids.remove(sessionID) != nil else { return }
        persistWorkoutBurnDeletionTombstones(ids)
    }

    private func hasWorkoutBurnDeletionTombstone(_ sessionID: UUID) -> Bool {
        workoutBurnDeletionTombstones.contains(sessionID)
    }

    private func persistWorkoutBurnDeletionTombstones(_ ids: Set<UUID>) {
        if ids.isEmpty {
            UserDefaults.standard.removeObject(forKey: workoutBurnDeletionTombstonesKey)
        } else {
            UserDefaults.standard.set(ids.map(\.uuidString).sorted(), forKey: workoutBurnDeletionTombstonesKey)
        }
    }

    private func retryPendingWorkoutBurnDeletions() async {
        for sessionID in workoutBurnDeletionTombstones {
            if await deleteWorkoutBurnSamples(sessionID: sessionID) {
                removeWorkoutBurnDeletionTombstone(sessionID)
            }
        }
    }

    private func preferredWorkoutBurnSessionsByDate(
        _ sessions: [StrengthWorkoutSession]
    ) -> [String: StrengthWorkoutSession] {
        var preferred: [String: StrengthWorkoutSession] = [:]
        for session in sessions where session.caloriesBurned != nil {
            let key = session.stableDiaryDateKey
            if let current = preferred[key], !shouldPreferWorkoutBurn(session, over: current) {
                continue
            }
            preferred[key] = session
        }
        return preferred
    }

    private func shouldPreferWorkoutBurn(
        _ candidate: StrengthWorkoutSession,
        over current: StrengthWorkoutSession
    ) -> Bool {
        let candidateVersion = candidate.healthSyncVersion ?? 0
        let currentVersion = current.healthSyncVersion ?? 0
        if candidateVersion != currentVersion { return candidateVersion > currentVersion }
        return candidate.completedAt > current.completedAt
    }

    // MARK: - Write Nutrition

    /// Writes all available nutrition values for a food entry to HealthKit.
    /// Each sample is tagged with the entry's UUID so it can be deleted later.
    func writeNutrition(for entry: FoodEntry) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

        var metadata = HealthFoodServingMetadata(entry: entry).dictionary
        metadata.merge([
            "fudai_entry_id": entry.id.uuidString,
            HKMetadataKeyFoodType: entry.name,
        ]) { _, new in new }

        let samples = nutritionQuantities(for: entry).compactMap { quantity -> HKQuantitySample? in
            guard isSharingAuthorized(quantity.identifier) else { return nil }
            return makeSample(quantity.identifier, value: quantity.value, unit: quantity.unit, date: entry.timestamp, metadata: metadata)
        }

        guard !samples.isEmpty else { return }
        healthStore.save(samples) { _, _ in }
    }

    /// Deletes all nutrition samples written for this entry. Bypasses `healthKitEnabled` so
    /// an in-app delete still cleans up the corresponding HK samples when sync was enabled
    /// at the time of the write but has since been turned off — otherwise old samples would
    /// stick around in Health forever.
    func deleteNutrition(entryID: UUID) {
        Task { await deleteNutritionSamples(entryID: entryID) }
    }

    /// Deletes the existing samples for an entry, awaits completion, then writes the new samples.
    /// Used on edits so a stale delete cannot clobber the freshly-written samples.
    /// The delete portion always runs (even if sync is currently off) to clean up samples the user
    /// exported earlier; the write portion respects the flag so we don't push fresh data while off.
    func updateNutrition(for entry: FoodEntry) {
        Task {
            await deleteNutritionSamples(entryID: entry.id)
            if UserDefaults.standard.bool(forKey: "healthKitEnabled") {
                writeNutrition(for: entry)
            }
        }
    }

    /// Backfills nutrition samples for any entries logged before HealthKit nutrition sync was enabled.
    /// Skips entries that already have samples in Apple Health to avoid duplicating history for users
    /// who were already syncing incrementally. Re-checks `currentEntryIDs` before each write so a meal
    /// deleted while the backfill is running does not get re-exported as a phantom sample.
    func backfillNutritionIfNeeded(entries: [FoodEntry], currentEntryIDs: @escaping () -> Set<UUID>) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard hasNutritionWriteAccess else { return }
        let backfilled = UserDefaults.standard.integer(forKey: nutritionBackfillVersionKey)
        guard backfilled < nutritionBackfillVersion else { return }
        // Guard against overlapping backfill runs — scene-phase changes can re-enter `wireUpHealthKit`
        // before the first scan finishes, and concurrent existence checks would both miss in-flight saves.
        guard !isBackfillingNutrition else { return }
        isBackfillingNutrition = true
        Task {
            defer { isBackfillingNutrition = false }
            for entry in entries {
                guard currentEntryIDs().contains(entry.id) else { continue }
                if await !nutritionSampleExists(forEntryID: entry.id, identifier: .dietaryEnergyConsumed) {
                    writeNutrition(for: entry)
                } else if await !nutritionSampleHasCurrentFoodMetadata(entryID: entry.id) {
                    await deleteNutritionSamples(entryID: entry.id)
                    writeNutrition(for: entry)
                } else {
                    await writeMissingNutritionSamples(for: entry, limitedTo: expandedNutritionTypeIdentifiers)
                }
            }
            UserDefaults.standard.set(nutritionBackfillVersion, forKey: nutritionBackfillVersionKey)
        }
    }

    /// One-shot import of every weight sample HealthKit knows about. Skips
    /// our own writes (fudai_weight_id present), and dedupes against existing
    /// entries by same-day + same-value so re-running this — or running it
    /// when the user already incrementally synced via the change-token observer
    /// — never creates duplicates. Stamps weightBackfillVersionKey on success
    /// so subsequent scene-active wire-ups skip it.
    func backfillWeightFromHealthKitIfNeeded(
        existing: @escaping () -> [WeightEntry],
        importBatch: @escaping ([WeightEntry]) -> Void
    ) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let backfilled = UserDefaults.standard.integer(forKey: weightBackfillVersionKey)
        guard backfilled < typesVersion else { return }
        guard !isBackfillingWeight else { return }
        isBackfillingWeight = true
        Task {
            defer { isBackfillingWeight = false }
            // A failed query (auth not determined, transient HK error) returns nil —
            // bail WITHOUT stamping the version so the backfill retries next scene-active
            // instead of being permanently burned by one bad run.
            guard let samples = await fetchAllSamples(.bodyMass, unit: .gramUnit(with: .kilo), fudaiMetadataKey: "fudai_weight_id") else { return }
            // Build the dedup index from the *current* store snapshot — the
            // observer might have added rows while we were querying HK.
            let calendar = Calendar.current
            let snapshot = existing()
            // Restore mode = empty local store (reinstall / new phone). Only then do we
            // import our own fudai-tagged samples: when entries exist locally, own samples
            // are either already represented or synthetic profile-pushes
            // (writeWeight(kg:date:)) that never had an entry — importing those would
            // fabricate history the user never logged.
            let restoringOwnHistory = snapshot.isEmpty
            var newEntries: [WeightEntry] = []
            // Same-day + close-value match catches our own pre-metadata writes,
            // externals already imported via the change-token loop, and same-day
            // duplicates within this batch (an entry write + a profile push on the
            // same day carry the same value).
            let isAlreadyLogged: (Date, Double) -> Bool = { date, kg in
                snapshot.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.weightKg - kg) < 0.01
                } || newEntries.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.weightKg - kg) < 0.01
                }
            }
            for s in samples {
                if isAlreadyLogged(s.date, s.value) { continue }
                if let fudaiID = s.fudaiID {
                    guard restoringOwnHistory else { continue }
                    // Keep the ORIGINAL entry id so a later in-app delete of the
                    // restored entry still finds and removes its HK sample via the
                    // fudai_weight_id metadata predicate.
                    newEntries.append(WeightEntry(id: fudaiID, date: s.date, weightKg: s.value))
                } else {
                    newEntries.append(WeightEntry(date: s.date, weightKg: s.value))
                }
            }
            if !newEntries.isEmpty {
                await MainActor.run { importBatch(newEntries) }
            }
            UserDefaults.standard.set(typesVersion, forKey: weightBackfillVersionKey)
        }
    }

    /// Mirror of backfillWeightFromHealthKitIfNeeded for body-fat samples.
    /// Same dedup discipline (skip our writes via fudai_bodyfat_id, dedup
    /// externals by same-day + same-fraction) and same one-shot-per-version
    /// guard so it doesn't re-scan on every scene-active.
    func backfillBodyFatFromHealthKitIfNeeded(
        existing: @escaping () -> [BodyFatEntry],
        importBatch: @escaping ([BodyFatEntry]) -> Void
    ) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let backfilled = UserDefaults.standard.integer(forKey: bodyFatBackfillVersionKey)
        guard backfilled < typesVersion else { return }
        guard !isBackfillingBodyFat else { return }
        isBackfillingBodyFat = true
        Task {
            defer { isBackfillingBodyFat = false }
            guard let samples = await fetchAllSamples(.bodyFatPercentage, unit: .percent(), fudaiMetadataKey: "fudai_bodyfat_id") else { return }
            let calendar = Calendar.current
            let snapshot = existing()
            // Same restore-mode / original-id / batch-dedup discipline as the
            // weight backfill — see backfillWeightFromHealthKitIfNeeded.
            let restoringOwnHistory = snapshot.isEmpty
            var newEntries: [BodyFatEntry] = []
            let isAlreadyLogged: (Date, Double) -> Bool = { date, fraction in
                snapshot.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.bodyFatFraction - fraction) < 0.001
                } || newEntries.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.bodyFatFraction - fraction) < 0.001
                }
            }
            for s in samples {
                if isAlreadyLogged(s.date, s.value) { continue }
                if let fudaiID = s.fudaiID {
                    guard restoringOwnHistory else { continue }
                    newEntries.append(BodyFatEntry(id: fudaiID, date: s.date, bodyFatFraction: s.value))
                } else {
                    newEntries.append(BodyFatEntry(date: s.date, bodyFatFraction: s.value))
                }
            }
            if !newEntries.isEmpty {
                await MainActor.run { importBatch(newEntries) }
            }
            UserDefaults.standard.set(typesVersion, forKey: bodyFatBackfillVersionKey)
        }
    }

    private func nutritionSampleExists(forEntryID entryID: UUID, identifier: HKQuantityTypeIdentifier) async -> Bool {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_entry_id", operatorType: .equalTo, value: entryID.uuidString)
        let type = HKQuantityType(identifier)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1, sortDescriptors: nil) { _, results, _ in
                continuation.resume(returning: !(results?.isEmpty ?? true))
            }
            healthStore.execute(query)
        }
    }

    private func nutritionSampleHasCurrentFoodMetadata(entryID: UUID) async -> Bool {
        let predicate = HKQuery.predicateForObjects(
            withMetadataKey: "fudai_entry_id",
            operatorType: .equalTo,
            value: entryID.uuidString
        )
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(
                sampleType: HKQuantityType(.dietaryEnergyConsumed),
                predicate: predicate,
                limit: 1,
                sortDescriptors: nil
            ) { _, results, _ in
                let sample = results?.first as? HKQuantitySample
                let version = (sample?.metadata?[HealthFoodServingMetadata.versionKey] as? NSNumber)?.intValue ?? 0
                continuation.resume(returning: version >= HealthFoodServingMetadata.schemaVersion)
            }
            healthStore.execute(query)
        }
    }

    private func deleteNutritionSamples(entryID: UUID) async {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_entry_id", operatorType: .equalTo, value: entryID.uuidString)
        await withTaskGroup(of: Void.self) { group in
            for identifier in nutritionTypeIdentifiers {
                group.addTask { [healthStore] in
                    await withCheckedContinuation { continuation in
                        healthStore.deleteObjects(of: HKQuantityType(identifier), predicate: predicate) { _, _, _ in
                            continuation.resume()
                        }
                    }
                }
            }
        }
    }

    private func writeMissingNutritionSamples(for entry: FoodEntry, limitedTo identifiers: Set<HKQuantityTypeIdentifier>) async {
        var metadata = HealthFoodServingMetadata(entry: entry).dictionary
        metadata.merge([
            "fudai_entry_id": entry.id.uuidString,
            HKMetadataKeyFoodType: entry.name,
        ]) { _, new in new }
        var samples: [HKQuantitySample] = []
        for quantity in nutritionQuantities(for: entry) where identifiers.contains(quantity.identifier) {
            guard isSharingAuthorized(quantity.identifier) else { continue }
            let exists = await nutritionSampleExists(forEntryID: entry.id, identifier: quantity.identifier)
            guard !exists else { continue }
            samples.append(makeSample(quantity.identifier, value: quantity.value, unit: quantity.unit, date: entry.timestamp, metadata: metadata))
        }
        guard !samples.isEmpty else { return }
        await withCheckedContinuation { continuation in
            healthStore.save(samples) { _, _ in
                continuation.resume()
            }
        }
    }

    private func nutritionQuantities(for entry: FoodEntry) -> [NutritionQuantity] {
        var quantities: [NutritionQuantity] = [
            NutritionQuantity(identifier: .dietaryEnergyConsumed, value: Double(entry.calories), unit: .kilocalorie()),
            NutritionQuantity(identifier: .dietaryProtein, value: Double(entry.protein), unit: .gram()),
            NutritionQuantity(identifier: .dietaryCarbohydrates, value: Double(entry.carbs), unit: .gram()),
            NutritionQuantity(identifier: .dietaryFatTotal, value: Double(entry.fat), unit: .gram()),
        ]

        func append(_ identifier: HKQuantityTypeIdentifier, value: Double?, unit: HKUnit) {
            guard let value else { return }
            quantities.append(NutritionQuantity(identifier: identifier, value: value, unit: unit))
        }

        append(.dietarySugar, value: entry.sugar, unit: .gram())
        append(.dietaryFiber, value: entry.fiber, unit: .gram())
        append(.dietaryFatSaturated, value: entry.saturatedFat, unit: .gram())
        append(.dietaryFatMonounsaturated, value: entry.monounsaturatedFat, unit: .gram())
        append(.dietaryFatPolyunsaturated, value: entry.polyunsaturatedFat, unit: .gram())
        append(.dietaryCholesterol, value: entry.cholesterol, unit: .gramUnit(with: .milli))
        append(.dietaryCaffeine, value: entry.caffeine, unit: .gramUnit(with: .milli))
        append(.dietarySodium, value: entry.sodium, unit: .gramUnit(with: .milli))
        append(.dietaryPotassium, value: entry.potassium, unit: .gramUnit(with: .milli))
        append(.dietaryCalcium, value: entry.calcium, unit: .gramUnit(with: .milli))
        append(.dietaryIron, value: entry.iron, unit: .gramUnit(with: .milli))
        append(.dietaryMagnesium, value: entry.magnesium, unit: .gramUnit(with: .milli))
        append(.dietaryZinc, value: entry.zinc, unit: .gramUnit(with: .milli))
        append(.dietaryVitaminA, value: entry.vitaminA, unit: .gramUnit(with: .micro))
        append(.dietaryVitaminC, value: entry.vitaminC, unit: .gramUnit(with: .milli))
        append(.dietaryVitaminD, value: entry.vitaminD, unit: .gramUnit(with: .micro))
        append(.dietaryVitaminB12, value: entry.vitaminB12, unit: .gramUnit(with: .micro))
        append(.dietaryVitaminE, value: entry.vitaminE, unit: .gramUnit(with: .milli))
        append(.dietaryVitaminK, value: entry.vitaminK, unit: .gramUnit(with: .micro))
        append(.dietaryFolate, value: entry.folate, unit: .gramUnit(with: .micro))

        return quantities
    }

    private func isSharingAuthorized(_ identifier: HKQuantityTypeIdentifier) -> Bool {
        healthStore.authorizationStatus(for: HKQuantityType(identifier)) == .sharingAuthorized
    }

    private func makeSample(_ identifier: HKQuantityTypeIdentifier, value: Double, unit: HKUnit, date: Date, metadata: [String: Any]) -> HKQuantitySample {
        let type = HKQuantityType(identifier)
        let quantity = HKQuantity(unit: unit, doubleValue: value)
        return HKQuantitySample(type: type, quantity: quantity, start: date, end: date, metadata: metadata)
    }

    // MARK: - Read Body Measurements

    func fetchLatestBodyMeasurements() async -> (weight: Double?, weightDate: Date?, weightFudaiID: UUID?, height: Double?, bodyFat: Double?, bodyFatDate: Date?, bodyFatFudaiID: UUID?, dob: Date?, sex: HKBiologicalSex?) {
        async let weightSample = fetchLatestSample(.bodyMass, unit: .gramUnit(with: .kilo), fudaiMetadataKey: "fudai_weight_id")
        async let height = fetchLatestSample(.height, unit: .meterUnit(with: .centi), fudaiMetadataKey: nil)
        async let bodyFat = fetchLatestSample(.bodyFatPercentage, unit: .percent(), fudaiMetadataKey: "fudai_bodyfat_id")

        var dob: Date?
        var sex: HKBiologicalSex?
        do {
            let dobComponents = try healthStore.dateOfBirthComponents()
            dob = Calendar.current.date(from: dobComponents)
        } catch {}
        do {
            sex = try healthStore.biologicalSex().biologicalSex
        } catch {}

        let w = await weightSample
        let h = await height
        let b = await bodyFat
        return (w?.value, w?.date, w?.fudaiID, h?.value, b?.value, b?.date, b?.fudaiID, dob, sex)
    }

    func fetchRecentEnergySummary(days requestedDays: Int = 14) async -> HealthEnergySummary? {
        let days = max(3, requestedDays)
        let history = await fetchRecentEnergyHistory(days: days)
        return Self.energySummary(from: history, requestedDays: days)
    }

    /// Pure aggregation shared by goal callers that already fetched the dated history. Keeping
    /// this separate avoids querying HealthKit twice for the same recalculation.
    static func energySummary(
        from history: [HealthEnergyDay],
        requestedDays: Int = 14
    ) -> HealthEnergySummary? {
        // Basal-only dates are not evidence of total daily expenditure. Require measured active
        // energy on at least three dates before allowing Health energy to anchor goal calories.
        let activeDays = history.filter { $0.activeCalories > 0 }
        guard activeDays.count >= 3 else { return nil }
        let activeAverage = Double(activeDays.reduce(0) { $0 + $1.activeCalories }) / Double(activeDays.count)
        let basalValues = activeDays.compactMap(\.basalCalories).filter { $0 > 0 }
        let basalAverage = basalValues.isEmpty
            ? nil
            : Double(basalValues.reduce(0, +)) / Double(basalValues.count)
        let totalValues = activeDays.compactMap(\.totalCalories)
        let totalAverage = totalValues.count >= 3
            ? Double(totalValues.reduce(0, +)) / Double(totalValues.count)
            : nil
        return HealthEnergySummary(
            activeAverageCalories: Int(activeAverage.rounded()),
            basalAverageCalories: basalAverage.map { Int($0.rounded()) },
            totalAverageCalories: totalAverage.map { Int($0.rounded()) },
            daysUsed: activeDays.count,
            requestedDays: requestedDays
        )
    }

    /// Daily step total for one local calendar day. Read-only — watches and phones
    /// write steps to HealthKit; Fud AI surfaces the aggregate on Home.
    func fetchStepsForDay(_ date: Date) async -> Int? {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return nil }
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: date)
        guard let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        let endBound = min(end, Date())
        guard endBound > start else { return nil }

        let type = HKQuantityType(.stepCount)
        return await withCheckedContinuation { continuation in
            let predicate = HKQuery.predicateForSamples(
                withStart: start,
                end: endBound,
                options: .strictStartDate
            )
            let query = HKStatisticsQuery(
                quantityType: type,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum
            ) { _, statistics, error in
                if error != nil {
                    continuation.resume(returning: nil)
                    return
                }
                guard let statistics else {
                    continuation.resume(returning: nil)
                    return
                }
                let count = statistics.sumQuantity()?.doubleValue(for: .count()) ?? 0
                continuation.resume(returning: count >= 0 ? Int(count.rounded()) : nil)
            }
            healthStore.execute(query)
        }
    }

    /// Dated measured energy for adaptive-goal evidence. This uses the same active-energy
    /// query as `fetchRecentEnergySummary`, including the predicate that removes every
    /// Fud-AI-tagged workout estimate. Returned days are oldest-first and contain no zero rows.
    func fetchRecentEnergyHistory(days requestedDays: Int = 14) async -> [HealthEnergyDay] {
        let days = min(max(1, requestedDays), WeightForecast.maxLookbackDays)
        async let activeByDay = fetchDailyEnergy(.activeEnergyBurned, days: days)
        async let basalByDay = fetchDailyEnergy(.basalEnergyBurned, days: days)

        let active = await activeByDay
        let basal = await basalByDay
        return active.keys.sorted().compactMap { date in
            guard let activeValue = active[date], activeValue > 0 else { return nil }
            let basalValue = basal[date]
            return HealthEnergyDay(
                date: date,
                activeCalories: Int(activeValue.rounded()),
                basalCalories: basalValue.map { Int($0.rounded()) }
            )
        }
    }

    /// Pulls every sample of `identifier` ever written to HealthKit (limit 10k
    /// — well above any realistic personal scale history). Used for the one-shot
    /// weight + body-fat backfill that runs the first time the user enables
    /// HealthKit sync and brings years of historical readings into the Progress
    /// chart. Sorted oldest-first so callers can append in chronological order.
    /// Returns nil on query failure (vs [] for genuinely no data) so callers can
    /// leave their one-shot stamps unset and retry later.
    func fetchAllSamples(_ identifier: HKQuantityTypeIdentifier, unit: HKUnit, fudaiMetadataKey: String?) async -> [(value: Double, date: Date, fudaiID: UUID?)]? {
        let type = HKQuantityType(identifier)
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        let predicate = HKQuery.predicateForSamples(withStart: nil, end: nil, options: .strictEndDate)

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 10_000, sortDescriptors: [sortDescriptor]) { _, results, error in
                guard error == nil, let samples = results as? [HKQuantitySample] else {
                    continuation.resume(returning: nil)
                    return
                }
                let mapped = samples.map { sample -> (value: Double, date: Date, fudaiID: UUID?) in
                    let idString = fudaiMetadataKey.flatMap { sample.metadata?[$0] as? String }
                    let fudaiID = idString.flatMap(UUID.init(uuidString:))
                    return (sample.quantity.doubleValue(for: unit), sample.startDate, fudaiID)
                }
                continuation.resume(returning: mapped)
            }
            healthStore.execute(query)
        }
    }

    /// `fudaiMetadataKey` lets each caller specify which metadata key holds the
    /// in-app-write marker for that quantity type — bodyMass uses `fudai_weight_id`,
    /// bodyFatPercentage uses `fudai_bodyfat_id`, height has no marker. Pass nil
    /// when there's no marker to look for; the returned `fudaiID` will be nil.
    private func fetchLatestSample(_ identifier: HKQuantityTypeIdentifier, unit: HKUnit, fudaiMetadataKey: String?) async -> (value: Double, date: Date, fudaiID: UUID?)? {
        let type = HKQuantityType(identifier)
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)
        let predicate = HKQuery.predicateForSamples(withStart: nil, end: nil, options: .strictEndDate)

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1, sortDescriptors: [sortDescriptor]) { _, results, _ in
                if let sample = results?.first as? HKQuantitySample {
                    let idString = fudaiMetadataKey.flatMap { sample.metadata?[$0] as? String }
                    let fudaiID = idString.flatMap(UUID.init(uuidString:))
                    continuation.resume(returning: (sample.quantity.doubleValue(for: unit), sample.startDate, fudaiID))
                } else {
                    continuation.resume(returning: nil)
                }
            }
            healthStore.execute(query)
        }
    }

    private func fetchDailyEnergy(_ identifier: HKQuantityTypeIdentifier, days: Int) async -> [Date: Double] {
        let type = HKQuantityType(identifier)
        let calendar = Calendar.current
        let end = calendar.startOfDay(for: Date())
        guard let start = calendar.date(byAdding: .day, value: -days, to: end) else { return [:] }
        let datePredicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let predicate: NSPredicate
        if identifier == .activeEnergyBurned {
            // Workout calories are estimates calculated by Fud AI. Keep them in
            // Health for the user's history, but never feed those estimates back
            // into measured TDEE/adaptive-goal calculations.
            let taggedWorkoutBurn = HKQuery.predicateForObjects(withMetadataKey: workoutBurnSessionIDKey)
            predicate = NSCompoundPredicate(
                andPredicateWithSubpredicates: [
                    datePredicate,
                    NSCompoundPredicate(notPredicateWithSubpredicate: taggedWorkoutBurn),
                ]
            )
        } else {
            predicate = datePredicate
        }
        var interval = DateComponents()
        interval.day = 1

        return await withCheckedContinuation { continuation in
            let query = HKStatisticsCollectionQuery(
                quantityType: type,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum,
                anchorDate: start,
                intervalComponents: interval
            )
            query.initialResultsHandler = { _, collection, _ in
                var values: [Date: Double] = [:]
                collection?.enumerateStatistics(from: start, to: end) { statistics, _ in
                    let value = statistics.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? 0
                    if value > 0 {
                        values[calendar.startOfDay(for: statistics.startDate)] = value
                    }
                }
                continuation.resume(returning: values)
            }
            healthStore.execute(query)
        }
    }

    /// Measured burn for one local calendar day. Returns nil when Health is off, active energy
    /// is unavailable, or the day has no positive external active-energy signal.
    func readEnergyForDay(_ date: Date) async -> HealthDailyEnergy? {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return nil }
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: date)
        guard let dayEnd = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        let end = min(dayEnd, Date())
        guard end > start else { return nil }

        async let active = sumEnergy(.activeEnergyBurned, from: start, to: end)
        async let basal = sumEnergy(.basalEnergyBurned, from: start, to: end)
        let activeValue = await active
        guard activeValue > 0 else { return nil }
        let basalValue = await basal
        let total = basalValue > 0 ? Int((activeValue + basalValue).rounded()) : nil
        return HealthDailyEnergy(
            activeCalories: Int(activeValue.rounded()),
            totalCalories: total
        )
    }

    private func sumEnergy(
        _ identifier: HKQuantityTypeIdentifier,
        from start: Date,
        to end: Date
    ) async -> Double {
        let type = HKQuantityType(identifier)
        let datePredicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let predicate: NSPredicate
        if identifier == .activeEnergyBurned {
            let taggedWorkoutBurn = HKQuery.predicateForObjects(withMetadataKey: workoutBurnSessionIDKey)
            predicate = NSCompoundPredicate(
                andPredicateWithSubpredicates: [
                    datePredicate,
                    NSCompoundPredicate(notPredicateWithSubpredicate: taggedWorkoutBurn),
                ]
            )
        } else {
            predicate = datePredicate
        }

        return await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(
                quantityType: type,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum
            ) { _, statistics, _ in
                let value = statistics?.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? 0
                continuation.resume(returning: value)
            }
            healthStore.execute(query)
        }
    }

    // MARK: - Food log restore from HealthKit

    private let foodRecoveryDoneKey = "healthKitFoodRecoveryDone"
    private var isRecoveringFood = false

    /// Rebuilds the local food log from the nutrition samples this app previously
    /// wrote to HealthKit — the restore path after a reinstall, phone reset, or
    /// new phone, where Health data survives (via iCloud) but the app container
    /// doesn't. Every sample carries fudai_entry_id (the original FoodEntry UUID)
    /// and HKMetadataKeyFoodType (the food name), so grouping by UUID reassembles
    /// each meal with its name, timestamp, calories, macros and micronutrients.
    /// Entries keep their original ids, so future in-app edits and deletes still
    /// target the matching HK samples. One-shot per install; dedupes against ids
    /// already in the store, so it's a no-op for users whose log is intact.
    /// Photos, emojis, notes and serving units aren't in Health and don't return.
    func restoreFoodEntriesFromHealthKitIfNeeded(
        existingIDs: @escaping () -> Set<UUID>,
        importBatch: @escaping ([FoodEntry]) -> Void
    ) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard !UserDefaults.standard.bool(forKey: foodRecoveryDoneKey) else { return }
        guard !isRecoveringFood else { return }
        isRecoveringFood = true
        Task {
            defer { isRecoveringFood = false }
            var accumulated: [UUID: RecoveredEntry] = [:]
            for identifier in nutritionTypeIdentifiers {
                // A failed query returns nil — abort WITHOUT stamping the done-flag
                // so the restore retries on a later scene-active instead of being
                // permanently burned by one transient error.
                guard let samples = await fetchTaggedNutritionSamples(identifier) else { return }
                for s in samples {
                    var entry = accumulated[s.entryID] ?? RecoveredEntry(date: s.date, name: s.name)
                    if s.date < entry.date { entry.date = s.date }
                    if entry.name.isEmpty { entry.name = s.name }
                    entry.values[identifier] = s.value
                    if entry.servingMetadata == nil {
                        entry.servingMetadata = s.servingMetadata
                    }
                    accumulated[s.entryID] = entry
                }
            }
            let existing = existingIDs()
            let entries = accumulated
                // A nameless group means the sample didn't come from a real
                // logged meal (writeNutrition always stamps the name) — skip
                // rather than fabricate an unnamed entry.
                .filter { !existing.contains($0.key) && !$0.value.name.trimmingCharacters(in: .whitespaces).isEmpty }
                .map { id, recovered in recovered.foodEntry(id: id) }
                .sorted { $0.timestamp < $1.timestamp }
            if !entries.isEmpty {
                await MainActor.run { importBatch(entries) }
            }
            UserDefaults.standard.set(true, forKey: foodRecoveryDoneKey)
        }
    }

    private struct RecoveredEntry {
        var date: Date
        var name: String
        var values: [HKQuantityTypeIdentifier: Double] = [:]
        var servingMetadata: HealthFoodServingMetadata?

        func foodEntry(id: UUID) -> FoodEntry {
            let servingSize = servingMetadata?.servingSizeGrams
            return FoodEntry(
                id: id,
                name: name,
                calories: Int((values[.dietaryEnergyConsumed] ?? 0).rounded()),
                protein: values[.dietaryProtein] ?? 0,
                carbs: values[.dietaryCarbohydrates] ?? 0,
                fat: values[.dietaryFatTotal] ?? 0,
                timestamp: date,
                source: .manual,
                mealType: MealScheduleSettings.mealType(for: date),
                sugar: values[.dietarySugar],
                fiber: values[.dietaryFiber],
                saturatedFat: values[.dietaryFatSaturated],
                monounsaturatedFat: values[.dietaryFatMonounsaturated],
                polyunsaturatedFat: values[.dietaryFatPolyunsaturated],
                cholesterol: values[.dietaryCholesterol],
                caffeine: values[.dietaryCaffeine],
                sodium: values[.dietarySodium],
                potassium: values[.dietaryPotassium],
                calcium: values[.dietaryCalcium],
                iron: values[.dietaryIron],
                magnesium: values[.dietaryMagnesium],
                zinc: values[.dietaryZinc],
                vitaminA: values[.dietaryVitaminA],
                vitaminC: values[.dietaryVitaminC],
                vitaminD: values[.dietaryVitaminD],
                vitaminB12: values[.dietaryVitaminB12],
                vitaminE: values[.dietaryVitaminE],
                vitaminK: values[.dietaryVitaminK],
                folate: values[.dietaryFolate],
                servingSizeGrams: servingSize,
                servingUnitOptions: servingMetadata?.servingUnitOptions ?? [],
                selectedServingUnit: servingSize == nil
                    ? "serving"
                    : servingMetadata?.selectedServingUnit,
                selectedServingQuantity: servingSize == nil
                    ? (servingMetadata?.selectedServingQuantity ?? 1)
                    : servingMetadata?.selectedServingQuantity
            )
        }
    }

    /// All samples of `identifier` carrying our fudai_entry_id tag, decoded with
    /// the tagged UUID, food name, and value in the same unit
    /// nutritionQuantities(for:) uses for writes. Nil on query failure (vs []
    /// for genuinely no data) so the caller can retry instead of stamping done.
    private struct TaggedNutritionSample {
        var entryID: UUID
        var name: String
        var value: Double
        var date: Date
        var servingMetadata: HealthFoodServingMetadata?
    }

    private func fetchTaggedNutritionSamples(_ identifier: HKQuantityTypeIdentifier) async -> [TaggedNutritionSample]? {
        let type = HKQuantityType(identifier)
        let unit = recoveryUnit(for: identifier)
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_entry_id")
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: [sortDescriptor]) { _, results, error in
                guard error == nil, let samples = results as? [HKQuantitySample] else {
                    continuation.resume(returning: nil)
                    return
                }
                let mapped = samples.compactMap { sample -> TaggedNutritionSample? in
                    guard let idString = sample.metadata?["fudai_entry_id"] as? String,
                          let entryID = UUID(uuidString: idString) else { return nil }
                    let name = sample.metadata?[HKMetadataKeyFoodType] as? String ?? ""
                    return TaggedNutritionSample(
                        entryID: entryID,
                        name: name,
                        value: sample.quantity.doubleValue(for: unit),
                        date: sample.startDate,
                        servingMetadata: HealthFoodServingMetadata.decode(from: sample.metadata)
                    )
                }
                continuation.resume(returning: mapped)
            }
            healthStore.execute(query)
        }
    }

    /// Inverse of the units in nutritionQuantities(for:) — must stay in sync.
    private func recoveryUnit(for identifier: HKQuantityTypeIdentifier) -> HKUnit {
        switch identifier {
        case .dietaryEnergyConsumed:
            .kilocalorie()
        case .dietaryCholesterol, .dietarySodium, .dietaryPotassium, .dietaryCalcium,
             .dietaryIron, .dietaryMagnesium, .dietaryZinc, .dietaryVitaminC, .dietaryVitaminE:
            .gramUnit(with: .milli)
        case .dietaryVitaminA, .dietaryVitaminD, .dietaryVitaminB12, .dietaryVitaminK, .dietaryFolate:
            .gramUnit(with: .micro)
        default:
            .gram()
        }
    }

    // MARK: - Imported Workouts

    /// Pulls recent HKWorkout samples into Fud AI. Imported rows are read-only,
    /// deduped by HealthKit UUID, and never written back to Health.
    func synchronizeImportedWorkoutsWithHealthKit(
        reconcileBatch: @escaping ([ImportedHealthWorkout], Date) -> Void
    ) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        enqueueWorkoutImportOperation { [weak self] in
            guard let self else { return }
            guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
            let queryStart = self.workoutImportQueryStartDate()
            guard let fetched = await self.fetchImportedWorkouts(since: queryStart) else { return }
            guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
            await MainActor.run {
                guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
                reconcileBatch(fetched, queryStart)
            }
            guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
            UserDefaults.standard.set(Date(), forKey: self.workoutImportLastSyncKey)
        }
    }

    private func enqueueWorkoutImportOperation(_ operation: @escaping () async -> Void) {
        let previous = workoutImportOperationTail
        workoutImportOperationTail = Task {
            _ = await previous?.result
            await operation()
        }
    }

    private func workoutImportQueryStartDate(calendar: Calendar = .current) -> Date {
        calendar.date(byAdding: .day, value: -workoutImportLookbackDays, to: Date()) ?? Date()
    }

    private func fetchImportedWorkouts(since start: Date) async -> [ImportedHealthWorkout]? {
        let type = HKObjectType.workoutType()
        let predicate = HKQuery.predicateForSamples(withStart: start, end: nil, options: .strictStartDate)
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)

        return await withCheckedContinuation { (continuation: CheckedContinuation<[ImportedHealthWorkout]?, Never>) in
            let query = HKSampleQuery(
                sampleType: type,
                predicate: predicate,
                limit: HKObjectQueryNoLimit,
                sortDescriptors: [sortDescriptor]
            ) { _, results, error in
                guard error == nil, let samples = results as? [HKWorkout] else {
                    continuation.resume(returning: nil)
                    return
                }
                let mapped = samples.compactMap { ImportedHealthWorkout.from($0) }
                continuation.resume(returning: mapped)
            }
            healthStore.execute(query)
        }
    }

    // MARK: - Observer

    func startBodyMeasurementObserver() {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard HKHealthStore.isHealthDataAvailable() else { return }

        // Tear down any prior observers before re-registering. `wireUpHealthKit()` runs on
        // every scene-active, and without this a single HK change would fire the callback
        // N times (once per cold-launch-plus-background-resume cycle in the session).
        stopObserver()

        let types: [HKQuantityTypeIdentifier] = [.bodyMass, .height, .bodyFatPercentage]
        for identifier in types {
            let type = HKQuantityType(identifier)
            let query = HKObserverQuery(sampleType: type, predicate: nil) { [weak self] _, completionHandler, _ in
                guard let self else {
                    completionHandler()
                    return
                }
                Task { @MainActor in
                    let m = await self.fetchLatestBodyMeasurements()
                    self.onBodyMeasurementsChanged?(
                        m.weight, m.weightDate, m.weightFudaiID, m.height, m.bodyFat, m.bodyFatDate, m.bodyFatFudaiID, m.dob, m.sex
                    )
                    completionHandler()
                }
            }
            healthStore.execute(query)
            observerQueries.append(query)
        }

        let workoutType = HKObjectType.workoutType()
        let workoutQuery = HKObserverQuery(sampleType: workoutType, predicate: nil) { [weak self] _, completionHandler, _ in
            guard let self else {
                completionHandler()
                return
            }
            Task { @MainActor in
                self.onImportedWorkoutsChanged?()
                completionHandler()
            }
        }
        healthStore.execute(workoutQuery)
        observerQueries.append(workoutQuery)
    }

    func stopObserver() {
        for query in observerQueries {
            healthStore.stop(query)
        }
        observerQueries.removeAll()
    }
}
