import Foundation
import SwiftUI

/// Local-only store for body-circumference history. Mirrors WeightStore / BodyFatStore but does
/// NOT sync anything back to UserProfile — circumferences are extra signal for the AI, not a
/// profile field. Entirely optional: an empty store means the feature is simply invisible to the
/// goal calc and the Coach.
@Observable
class BodyMeasurementStore {
    private(set) var entries: [BodyMeasurement] = []

    private let storageKey = "bodyMeasurementEntries"

    init() {
        loadEntries()
    }

    var latestEntry: BodyMeasurement? {
        entries.sorted { $0.date > $1.date }.first
    }

    /// Newest-first, for history lists.
    var sortedEntries: [BodyMeasurement] {
        entries.sorted { $0.date > $1.date }
    }

    func addEntry(_ entry: BodyMeasurement) {
        guard entry.hasAnyValue else { return }
        entries.append(entry)
        saveEntries()
    }

    /// Set one site's value. Editing several sites the same day updates today's single snapshot;
    /// the first edit on a new day starts a fresh dated snapshot carrying the previous values
    /// forward (so the latest entry always holds the user's current full set). `nil` clears a site.
    func setValue(_ site: BodyMeasurement.Site, cm: Double?) {
        if let latest = latestEntry, Calendar.current.isDateInToday(latest.date) {
            let updated = latest.setting(site, to: cm)
            entries.removeAll { $0.id == latest.id }
            if updated.hasAnyValue { entries.append(updated) }
            saveEntries()
        } else {
            var fresh = BodyMeasurement()
            if let latest = latestEntry {
                for s in BodyMeasurement.Site.allCases {
                    fresh = fresh.setting(s, to: latest.value(for: s))
                }
            }
            fresh = fresh.setting(site, to: cm)
            if fresh.hasAnyValue {
                entries.append(fresh)
                saveEntries()
            }
        }
    }

    func deleteEntry(_ entry: BodyMeasurement) {
        let id = entry.id
        entries.removeAll { $0.id == id }
        saveEntries()
    }

    func reloadFromDefaults() {
        loadEntries()
    }

    func replaceAllEntries(_ newEntries: [BodyMeasurement]) {
        entries = newEntries
        saveEntries()
    }

    private func saveEntries() {
        if let data = try? JSONEncoder().encode(entries) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func loadEntries() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([BodyMeasurement].self, from: data)
        else { return }
        entries = decoded
    }
}
