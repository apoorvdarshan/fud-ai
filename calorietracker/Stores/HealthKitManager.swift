import Foundation
import HealthKit

@Observable
class HealthKitManager {
    var authorizationStatus: HKAuthorizationStatus = .notDetermined

    var onBodyMeasurementsChanged: ((Double?, Double?, Double?, Date?, HKBiologicalSex?) -> Void)?

    private let healthStore = HKHealthStore()
    private var observerQueries: [HKObserverQuery] = []

    // MARK: - Types

    /// Bump this when adding new HealthKit types so we can re-request authorization
    /// for users who already authorized the old set.
    private let authVersion = 2
    private let authVersionKey = "healthKitAuthVersion"

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
        let stored = UserDefaults.standard.integer(forKey: authVersionKey)
        let enabled = UserDefaults.standard.bool(forKey: "healthKitEnabled")
        return enabled && stored < authVersion
    }

    // MARK: - Authorization

    func requestAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        do {
            try await healthStore.requestAuthorization(toShare: shareTypes, read: readTypes)
            authorizationStatus = healthStore.authorizationStatus(for: HKQuantityType(.bodyMass))
            // Only persist this auth version once dietary write access is actually granted,
            // otherwise users who deny nutrition can never get re-prompted.
            if dietaryShareTypes.allSatisfy({ healthStore.authorizationStatus(for: $0) == .sharingAuthorized }) {
                UserDefaults.standard.set(authVersion, forKey: authVersionKey)
            }
            return true
        } catch {
            return false
        }
    }

    /// Whether HealthKit currently has write permission for nutrition samples.
    var hasNutritionWriteAccess: Bool {
        dietaryShareTypes.allSatisfy { healthStore.authorizationStatus(for: $0) == .sharingAuthorized }
    }

    // MARK: - Write Body Measurements

    func writeWeight(kg: Double, date: Date) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        let type = HKQuantityType(.bodyMass)
        let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: kg)
        let sample = HKQuantitySample(type: type, quantity: quantity, start: date, end: date)
        healthStore.save(sample) { _, _ in }
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
        let sample = HKQuantitySample(type: type, quantity: quantity, start: .now, end: .now)
        healthStore.save(sample) { _, _ in }
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

    /// Deletes all nutrition samples written for this entry.
    func deleteNutrition(entryID: UUID) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        Task { await deleteNutritionSamples(entryID: entryID) }
    }

    /// Deletes nutrition samples for the given entries regardless of the current `healthKitEnabled` flag.
    /// Used by destructive reset paths so previously-exported samples are purged even after the user
    /// turned off HealthKit sync.
    func purgeNutrition(entryIDs: [UUID]) {
        guard !entryIDs.isEmpty else { return }
        Task {
            for id in entryIDs {
                await deleteNutritionSamples(entryID: id)
            }
        }
    }

    /// Deletes the existing samples for an entry, awaits completion, then writes the new samples.
    /// Used on edits so a stale delete cannot clobber the freshly-written samples.
    func updateNutrition(for entry: FoodEntry) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        Task {
            await deleteNutritionSamples(entryID: entry.id)
            writeNutrition(for: entry)
        }
    }

    /// Backfills nutrition samples for any entries logged before HealthKit nutrition sync was enabled.
    /// Skips entries that already have samples in Apple Health to avoid duplicating history for users
    /// who were already syncing incrementally.
    func backfillNutritionIfNeeded(entries: [FoodEntry]) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard hasNutritionWriteAccess else { return }
        let backfilled = UserDefaults.standard.integer(forKey: nutritionBackfillVersionKey)
        guard backfilled < authVersion else { return }
        Task {
            for entry in entries where await !nutritionSampleExists(forEntryID: entry.id) {
                writeNutrition(for: entry)
            }
            UserDefaults.standard.set(authVersion, forKey: nutritionBackfillVersionKey)
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

    func fetchLatestBodyMeasurements() async -> (weight: Double?, height: Double?, bodyFat: Double?, dob: Date?, sex: HKBiologicalSex?) {
        async let weight = fetchLatestQuantity(.bodyMass, unit: .gramUnit(with: .kilo))
        async let height = fetchLatestQuantity(.height, unit: .meterUnit(with: .centi))
        async let bodyFat = fetchLatestQuantity(.bodyFatPercentage, unit: .percent())

        var dob: Date?
        var sex: HKBiologicalSex?
        do {
            let dobComponents = try healthStore.dateOfBirthComponents()
            dob = Calendar.current.date(from: dobComponents)
        } catch {}
        do {
            sex = try healthStore.biologicalSex().biologicalSex
        } catch {}

        return (await weight, await height, await bodyFat, dob, sex)
    }

    private func fetchLatestQuantity(_ identifier: HKQuantityTypeIdentifier, unit: HKUnit) async -> Double? {
        let type = HKQuantityType(identifier)
        let sortDescriptor = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)
        let predicate = HKQuery.predicateForSamples(withStart: nil, end: nil, options: .strictEndDate)

        return await withCheckedContinuation { continuation in
            let query = HKSampleQuery(sampleType: type, predicate: predicate, limit: 1, sortDescriptors: [sortDescriptor]) { _, results, _ in
                let value = (results?.first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
                continuation.resume(returning: value)
            }
            healthStore.execute(query)
        }
    }

    // MARK: - Observer

    func startBodyMeasurementObserver() {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard HKHealthStore.isHealthDataAvailable() else { return }

        let types: [HKQuantityTypeIdentifier] = [.bodyMass, .height, .bodyFatPercentage]
        for identifier in types {
            let type = HKQuantityType(identifier)
            let query = HKObserverQuery(sampleType: type, predicate: nil) { [weak self] _, completionHandler, _ in
                guard let self else {
                    completionHandler()
                    return
                }
                Task {
                    let measurements = await self.fetchLatestBodyMeasurements()
                    self.onBodyMeasurementsChanged?(
                        measurements.weight,
                        measurements.height,
                        measurements.bodyFat,
                        measurements.dob,
                        measurements.sex
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
