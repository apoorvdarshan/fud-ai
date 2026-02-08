import Foundation
import HealthKit

@Observable
class HealthKitManager {
    var authorizationStatus: HKAuthorizationStatus = .notDetermined

    var onBodyMeasurementsChanged: ((Double?, Double?, Double?, Date?, HKBiologicalSex?) -> Void)?

    private let healthStore = HKHealthStore()
    private var observerQueries: [HKObserverQuery] = []
    private let sampleMapKey = "healthKitSampleMap"

    // MARK: - Types

    private var shareTypes: Set<HKSampleType> {
        [
            HKQuantityType(.dietaryEnergyConsumed),
            HKQuantityType(.dietaryProtein),
            HKQuantityType(.dietaryCarbohydrates),
            HKQuantityType(.dietaryFatTotal),
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
        ]
    }

    private var readTypes: Set<HKObjectType> {
        [
            HKQuantityType(.bodyMass),
            HKQuantityType(.height),
            HKQuantityType(.bodyFatPercentage),
            HKCharacteristicType(.dateOfBirth),
            HKCharacteristicType(.biologicalSex),
        ]
    }

    // MARK: - Authorization

    func requestAuthorization() async -> Bool {
        guard HKHealthStore.isHealthDataAvailable() else { return false }
        do {
            try await healthStore.requestAuthorization(toShare: shareTypes, read: readTypes)
            authorizationStatus = healthStore.authorizationStatus(for: HKQuantityType(.bodyMass))
            return true
        } catch {
            return false
        }
    }

    // MARK: - Write Nutrition

    func writeNutrition(entryId: UUID, calories: Int, protein: Int, carbs: Int, fat: Int, date: Date) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

        let samples: [(HKQuantityTypeIdentifier, Double, HKUnit)] = [
            (.dietaryEnergyConsumed, Double(calories), .kilocalorie()),
            (.dietaryProtein, Double(protein), .gram()),
            (.dietaryCarbohydrates, Double(carbs), .gram()),
            (.dietaryFatTotal, Double(fat), .gram()),
        ]

        var sampleUUIDs: [String] = []
        for (identifier, value, unit) in samples {
            let type = HKQuantityType(identifier)
            let quantity = HKQuantity(unit: unit, doubleValue: value)
            let sample = HKQuantitySample(type: type, quantity: quantity, start: date, end: date)
            sampleUUIDs.append(sample.uuid.uuidString)
            healthStore.save(sample) { _, _ in }
        }
        saveSampleUUIDs(sampleUUIDs, for: entryId)
    }

    // MARK: - Delete Nutrition

    func deleteNutrition(for entryId: UUID) {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }
        guard let uuidStrings = loadSampleUUIDs(for: entryId) else { return }

        let uuids = uuidStrings.compactMap { UUID(uuidString: $0) }
        let predicate = HKQuery.predicateForObjects(with: Set(uuids))

        let types: [HKQuantityTypeIdentifier] = [
            .dietaryEnergyConsumed, .dietaryProtein, .dietaryCarbohydrates, .dietaryFatTotal
        ]
        for identifier in types {
            let type = HKQuantityType(identifier)
            healthStore.deleteObjects(of: type, predicate: predicate) { _, _, _ in }
        }
        removeSampleUUIDs(for: entryId)
    }

    // MARK: - Sample UUID Mapping

    private func saveSampleUUIDs(_ uuids: [String], for entryId: UUID) {
        var map = UserDefaults.standard.dictionary(forKey: sampleMapKey) as? [String: [String]] ?? [:]
        map[entryId.uuidString] = uuids
        UserDefaults.standard.set(map, forKey: sampleMapKey)
    }

    private func loadSampleUUIDs(for entryId: UUID) -> [String]? {
        let map = UserDefaults.standard.dictionary(forKey: sampleMapKey) as? [String: [String]] ?? [:]
        return map[entryId.uuidString]
    }

    private func removeSampleUUIDs(for entryId: UUID) {
        var map = UserDefaults.standard.dictionary(forKey: sampleMapKey) as? [String: [String]] ?? [:]
        map.removeValue(forKey: entryId.uuidString)
        UserDefaults.standard.set(map, forKey: sampleMapKey)
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
