import ExpoModulesCore
import HealthKit

public class HealthSyncModule: Module {
  private let store = HKHealthStore()

  public func definition() -> ModuleDefinition {
    Name("HealthSync")

    Constants([
      "isAvailable": HKHealthStore.isHealthDataAvailable()
    ])

    AsyncFunction("authorizationStatus") { () -> String in
      guard HKHealthStore.isHealthDataAvailable() else { return "unavailable" }
      switch store.authorizationStatus(for: HKQuantityType(.bodyMass)) {
      case .sharingAuthorized: return "authorized"
      case .sharingDenied: return "denied"
      default: return "notDetermined"
      }
    }

    AsyncFunction("requestAuthorization") { () async -> Bool in
      guard HKHealthStore.isHealthDataAvailable() else { return false }
      do {
        try await store.requestAuthorization(toShare: Self.shareTypes, read: Self.readTypes)
        return Self.requiredWriteTypes.allSatisfy { store.authorizationStatus(for: $0) == .sharingAuthorized }
      } catch {
        return false
      }
    }

    AsyncFunction("writeWeight") { (kg: Double, dateMs: Double, entryId: String?) in
      let type = HKQuantityType(.bodyMass)
      let quantity = HKQuantity(unit: .gramUnit(with: .kilo), doubleValue: kg)
      let date = Date(timeIntervalSince1970: dateMs / 1000)
      var metadata: [String: Any] = [:]
      if let entryId { metadata["fudai_weight_id"] = entryId }
      let sample = HKQuantitySample(type: type, quantity: quantity, start: date, end: date, metadata: metadata)
      try await store.save(sample)
    }

    AsyncFunction("writeBodyFat") { (fraction: Double, dateMs: Double, entryId: String?) in
      let type = HKQuantityType(.bodyFatPercentage)
      let quantity = HKQuantity(unit: .percent(), doubleValue: fraction)
      let date = Date(timeIntervalSince1970: dateMs / 1000)
      var metadata: [String: Any] = [:]
      if let entryId { metadata["fudai_bodyfat_id"] = entryId }
      let sample = HKQuantitySample(type: type, quantity: quantity, start: date, end: date, metadata: metadata)
      try await store.save(sample)
    }

    AsyncFunction("writeNutrition") { (payload: [String: Any]) in
      guard let dateMs = payload["dateMs"] as? Double else { return }
      let date = Date(timeIntervalSince1970: dateMs / 1000)
      var metadata: [String: Any] = [:]
      if let entryId = payload["entryId"] as? String { metadata["fudai_entry_id"] = entryId }
      if let name = payload["name"] as? String { metadata[HKMetadataKeyFoodType] = name }
      var samples: [HKQuantitySample] = []
      func add(_ identifier: HKQuantityTypeIdentifier, value: Double?, unit: HKUnit) {
        guard let value, store.authorizationStatus(for: HKQuantityType(identifier)) == .sharingAuthorized else { return }
        samples.append(HKQuantitySample(
          type: HKQuantityType(identifier),
          quantity: HKQuantity(unit: unit, doubleValue: value),
          start: date,
          end: date,
          metadata: metadata
        ))
      }
      add(.dietaryEnergyConsumed, value: payload["calories"] as? Double, unit: .kilocalorie())
      add(.dietaryProtein, value: payload["protein"] as? Double, unit: .gram())
      add(.dietaryCarbohydrates, value: payload["carbs"] as? Double, unit: .gram())
      add(.dietaryFatTotal, value: payload["fat"] as? Double, unit: .gram())
      guard !samples.isEmpty else { return }
      try await store.save(samples)
    }

    AsyncFunction("deleteNutrition") { (entryId: String) in
      try await deleteSamples(metadataKey: "fudai_entry_id", entryId: entryId, identifiers: [
        .dietaryEnergyConsumed, .dietaryProtein, .dietaryCarbohydrates, .dietaryFatTotal
      ])
    }

    AsyncFunction("deleteWeight") { (entryId: String) in
      try await deleteSamples(metadataKey: "fudai_weight_id", entryId: entryId, identifiers: [.bodyMass])
    }

    AsyncFunction("deleteBodyFat") { (entryId: String) in
      try await deleteSamples(metadataKey: "fudai_bodyfat_id", entryId: entryId, identifiers: [.bodyFatPercentage])
    }

    AsyncFunction("readSteps") { (startMs: Double, endMs: Double) async -> Int? in
      let start = Date(timeIntervalSince1970: startMs / 1000)
      let end = Date(timeIntervalSince1970: endMs / 1000)
      guard end > start else { return nil }
      let type = HKQuantityType(.stepCount)
      return await withCheckedContinuation { continuation in
        let query = HKStatisticsQuery(
          quantityType: type,
          quantitySamplePredicate: HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate),
          options: .cumulativeSum
        ) { _, statistics, _ in
          let count = statistics?.sumQuantity()?.doubleValue(for: .count()) ?? 0
          continuation.resume(returning: count >= 0 ? Int(count.rounded()) : nil)
        }
        store.execute(query)
      }
    }
  }

  private func deleteSamples(metadataKey: String, entryId: String, identifiers: [HKQuantityTypeIdentifier]) async throws {
    let predicate = HKQuery.predicateForObjects(withMetadataKey: metadataKey, operatorType: .equalTo, value: entryId)
    for identifier in identifiers {
      try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        store.deleteObjects(of: HKQuantityType(identifier), predicate: predicate) { _, _, error in
          if let error { continuation.resume(throwing: error) }
          else { continuation.resume() }
        }
      }
    }
  }

  private static var requiredWriteTypes: [HKQuantityType] {
    [
      HKQuantityType(.bodyMass),
      HKQuantityType(.bodyFatPercentage),
      HKQuantityType(.dietaryEnergyConsumed),
      HKQuantityType(.dietaryProtein),
      HKQuantityType(.dietaryCarbohydrates),
      HKQuantityType(.dietaryFatTotal),
    ]
  }

  private static var shareTypes: Set<HKSampleType> {
    [
      HKQuantityType(.bodyMass),
      HKQuantityType(.height),
      HKQuantityType(.bodyFatPercentage),
      HKQuantityType(.dietaryEnergyConsumed),
      HKQuantityType(.dietaryProtein),
      HKQuantityType(.dietaryCarbohydrates),
      HKQuantityType(.dietaryFatTotal),
    ]
  }

  private static var readTypes: Set<HKObjectType> {
    var types: Set<HKObjectType> = shareTypes
    types.insert(HKQuantityType(.stepCount))
    return types
  }
}
