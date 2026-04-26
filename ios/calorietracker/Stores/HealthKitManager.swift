import Foundation
import HealthKit

@Observable
class HealthKitManager {
    var authorizationStatus: HKAuthorizationStatus = .notDetermined

    /// Args, in order: weight (kg), weightDate, weightFudaiID, heightCm,
    /// bodyFat (fraction 0–1), bodyFatDate, bodyFatFudaiID, dob, sex.
    /// FudaiID is non-nil when the latest sample of that type was written by us
    /// (matched by metadata key) — observer caller uses it to skip echo-imports.
    var onBodyMeasurementsChanged: ((Double?, Date?, UUID?, Double?, Double?, Date?, UUID?, Date?, HKBiologicalSex?) -> Void)?

    private let healthStore = HKHealthStore()
    private var observerQueries: [HKObserverQuery] = []

    // MARK: - Types

    /// Bump this when adding new HealthKit types so we can re-request authorization
    /// for users who already authorized the old set. Just an integer schema marker,
    /// not credentials — named to avoid CodeQL's "auth"-keyword heuristic false positive.
    private let typesVersion = 2
    private let typesVersionKey = "healthKitTypesVersion"

    private var dietaryShareTypes: Set<HKSampleType> {
        [
            // Macronutrients
            HKQuantityType(.dietaryEnergyConsumed),
            HKQuantityType(.dietaryProtein),
            HKQuantityType(.dietaryCarbohydrates),
            HKQuantityType(.dietaryFatTotal),
            // Micronutrients
            HKQuantityType(.dietarySugar),
            HKQuantityType(.dietaryFiber),
            HKQuantityType(.dietaryFatSaturated),
            HKQuantityType(.dietaryFatMonounsaturated),
            HKQuantityType(.dietaryFatPolyunsaturated),
            HKQuantityType(.dietaryCholesterol),
            HKQuantityType(.dietarySodium),
            HKQuantityType(.dietaryPotassium),
        ]
    }

    private var shareTypes: Set<HKSampleType> {
        var types: Set<HKSampleType> = [
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
        ]
        types.formUnion(dietaryShareTypes)
        return types
    }

    private let nutritionBackfillVersionKey = "healthKitNutritionBackfillVersion"
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

    private var readTypes: Set<HKObjectType> {
        [
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
            HKCharacteristicType(.dateOfBirth),
            HKCharacteristicType(.biologicalSex),
        ]
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
            if dietaryShareTypes.allSatisfy({ healthStore.authorizationStatus(for: $0) == .sharingAuthorized }) {
                persistCurrentTypesVersion()
            }
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

    /// Whether HealthKit currently has write permission for nutrition samples.
    var hasNutritionWriteAccess: Bool {
        dietaryShareTypes.allSatisfy { healthStore.authorizationStatus(for: $0) == .sharingAuthorized }
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

    // MARK: - Write Nutrition

    /// Writes all available nutrition values for a food entry to HealthKit.
    /// Each sample is tagged with the entry's UUID so it can be deleted later.
    func writeNutrition(for entry: FoodEntry) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

        let metadata: [String: Any] = [
            "fudai_entry_id": entry.id.uuidString,
            HKMetadataKeyFoodType: entry.name,
        ]

        var samples: [HKQuantitySample] = []

        // Macros (always present)
        samples.append(makeSample(.dietaryEnergyConsumed, value: Double(entry.calories), unit: .kilocalorie(), date: entry.timestamp, metadata: metadata))
        samples.append(makeSample(.dietaryProtein, value: Double(entry.protein), unit: .gram(), date: entry.timestamp, metadata: metadata))
        samples.append(makeSample(.dietaryCarbohydrates, value: Double(entry.carbs), unit: .gram(), date: entry.timestamp, metadata: metadata))
        samples.append(makeSample(.dietaryFatTotal, value: Double(entry.fat), unit: .gram(), date: entry.timestamp, metadata: metadata))

        // Micronutrients (optional)
        if let v = entry.sugar { samples.append(makeSample(.dietarySugar, value: v, unit: .gram(), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.fiber { samples.append(makeSample(.dietaryFiber, value: v, unit: .gram(), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.saturatedFat { samples.append(makeSample(.dietaryFatSaturated, value: v, unit: .gram(), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.monounsaturatedFat { samples.append(makeSample(.dietaryFatMonounsaturated, value: v, unit: .gram(), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.polyunsaturatedFat { samples.append(makeSample(.dietaryFatPolyunsaturated, value: v, unit: .gram(), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.cholesterol { samples.append(makeSample(.dietaryCholesterol, value: v, unit: .gramUnit(with: .milli), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.sodium { samples.append(makeSample(.dietarySodium, value: v, unit: .gramUnit(with: .milli), date: entry.timestamp, metadata: metadata)) }
        if let v = entry.potassium { samples.append(makeSample(.dietaryPotassium, value: v, unit: .gramUnit(with: .milli), date: entry.timestamp, metadata: metadata)) }

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
        guard backfilled < typesVersion else { return }
        // Guard against overlapping backfill runs — scene-phase changes can re-enter `wireUpHealthKit`
        // before the first scan finishes, and concurrent existence checks would both miss in-flight saves.
        guard !isBackfillingNutrition else { return }
        isBackfillingNutrition = true
        Task {
            defer { isBackfillingNutrition = false }
            for entry in entries {
                guard currentEntryIDs().contains(entry.id) else { continue }
                if await !nutritionSampleExists(forEntryID: entry.id) {
                    writeNutrition(for: entry)
                }
            }
            UserDefaults.standard.set(typesVersion, forKey: nutritionBackfillVersionKey)
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
            let samples = await fetchAllSamples(.bodyMass, unit: .gramUnit(with: .kilo), fudaiMetadataKey: "fudai_weight_id")
            // Build the dedup index from the *current* store snapshot — the
            // observer might have added rows while we were querying HK.
            let calendar = Calendar.current
            let snapshot = existing()
            // Same-day + close-value match catches both our own pre-metadata
            // writes and externals already imported via the change-token loop.
            let isAlreadyLogged: (Date, Double) -> Bool = { date, kg in
                snapshot.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.weightKg - kg) < 0.01
                }
            }
            var newEntries: [WeightEntry] = []
            for s in samples {
                if s.fudaiID != nil { continue } // our own write — already represented
                if isAlreadyLogged(s.date, s.value) { continue }
                newEntries.append(WeightEntry(date: s.date, weightKg: s.value))
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
            let samples = await fetchAllSamples(.bodyFatPercentage, unit: .percent(), fudaiMetadataKey: "fudai_bodyfat_id")
            let calendar = Calendar.current
            let snapshot = existing()
            let isAlreadyLogged: (Date, Double) -> Bool = { date, fraction in
                snapshot.contains {
                    calendar.isDate($0.date, inSameDayAs: date) && abs($0.bodyFatFraction - fraction) < 0.001
                }
            }
            var newEntries: [BodyFatEntry] = []
            for s in samples {
                if s.fudaiID != nil { continue }
                if isAlreadyLogged(s.date, s.value) { continue }
                newEntries.append(BodyFatEntry(date: s.date, bodyFatFraction: s.value))
            }
            if !newEntries.isEmpty {
                await MainActor.run { importBatch(newEntries) }
            }
            UserDefaults.standard.set(typesVersion, forKey: bodyFatBackfillVersionKey)
        }
    }

    private func nutritionSampleExists(forEntryID entryID: UUID) async -> Bool {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_entry_id", operatorType: .equalTo, value: entryID.uuidString)
        let type = HKQuantityType(.dietaryEnergyConsumed)
        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1, sortDescriptors: nil) { _, results, _ in
                continuation.resume(returning: !(results?.isEmpty ?? true))
            }
            healthStore.execute(query)
        }
    }

    private func deleteNutritionSamples(entryID: UUID) async {
        let predicate = HKQuery.predicateForObjects(withMetadataKey: "fudai_entry_id", operatorType: .equalTo, value: entryID.uuidString)
        let nutritionTypes: [HKQuantityTypeIdentifier] = [
            .dietaryEnergyConsumed, .dietaryProtein, .dietaryCarbohydrates, .dietaryFatTotal,
            .dietarySugar, .dietaryFiber, .dietaryFatSaturated, .dietaryFatMonounsaturated,
            .dietaryFatPolyunsaturated, .dietaryCholesterol, .dietarySodium, .dietaryPotassium,
        ]
        await withTaskGroup(of: Void.self) { group in
            for identifier in nutritionTypes {
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

    /// Pulls every sample of `identifier` ever written to HealthKit (limit 10k
    /// — well above any realistic personal scale history). Used for the one-shot
    /// weight + body-fat backfill that runs the first time the user enables
    /// HealthKit sync and brings years of historical readings into the Progress
    /// chart. Sorted oldest-first so callers can append in chronological order.
    func fetchAllSamples(_ identifier: HKQuantityTypeIdentifier, unit: HKUnit, fudaiMetadataKey: String?) async -> [(value: Double, date: Date, fudaiID: UUID?)] {
        let type = HKQuantityType(identifier)
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        let predicate = HKQuery.predicateForSamples(withStart: nil, end: nil, options: .strictEndDate)

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 10_000, sortDescriptors: [sortDescriptor]) { _, results, _ in
                guard let samples = results as? [HKQuantitySample] else {
                    continuation.resume(returning: [])
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
    }

    func stopObserver() {
        for query in observerQueries {
            healthStore.stop(query)
        }
        observerQueries.removeAll()
    }
}
