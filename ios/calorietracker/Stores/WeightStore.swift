import Foundation
import SwiftUI

@Observable
class WeightStore {
    private(set) var entries: [WeightEntry] = []
    var onEntryAdded: ((WeightEntry) -> Void)?
    var onEntryDeleted: ((UUID) -> Void)?

    static let storageKey = "weightEntries"
    private let observesExternalChanges: Bool
    private let entriesBlob: PersistedBlobGuard

    /// True while an unreadable weight blob is on disk without a backup copy;
    /// mutations are refused so they cannot overwrite the user's history.
    var isPersistenceBlocked: Bool { entriesBlob.isWriteBlocked }
    var corruptBlobBackups: [PersistedBlobGuard.CorruptBackup] { entriesBlob.backups }

    static let externalChangeNotification = "ai.fud.weightEntriesDidChange"

    init(
        observesExternalChanges: Bool = true,
        defaults: UserDefaults = .standard,
        corruptBackupDirectory: URL? = nil
    ) {
        self.observesExternalChanges = observesExternalChanges
        self.entriesBlob = PersistedBlobGuard(defaults: defaults, key: Self.storageKey, backupDirectory: corruptBackupDirectory)
        loadEntries(isInitialLoad: true)
        if observesExternalChanges {
            startObservingExternalChanges()
        }
        // No default seed — WeightStore.init runs before onboarding finishes on a fresh
        // install, so `UserProfile.load()` is nil and the old seed fell back to .default
        // (70 kg), dropping a phantom 70 kg entry onto every new user's chart even if
        // their real weight was different. Onboarding now seeds the first WeightEntry
        // via `seedInitialWeightFromProfileIfEmpty(_:)` once the profile is real.
    }

    deinit {
        guard observesExternalChanges else { return }
        CFNotificationCenterRemoveObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque()),
            CFNotificationName(Self.externalChangeNotification as CFString),
            nil
        )
    }

    /// Add the first WeightEntry from the user's onboarding-set profile weight.
    /// Safe to call multiple times — no-op if any entries already exist, so subsequent
    /// scene-active firings or re-onboarding paths can't duplicate.
    func seedInitialWeightFromProfileIfEmpty(_ weightKg: Double) {
        guard entries.isEmpty else { return }
        addEntry(WeightEntry(date: .now, weightKg: weightKg))
    }

    var latestEntry: WeightEntry? {
        entries.sorted { $0.date > $1.date }.first
    }

    func entries(in range: ClosedRange<Date>) -> [WeightEntry] {
        entries
            .filter { range.contains($0.date) }
            .sorted { $0.date < $1.date }
    }

    func addEntry(_ entry: WeightEntry) {
        guard !isPersistenceBlocked else { return }
        let previousLatest = entries.sorted { $0.date > $1.date }.first
        entries.append(entry)
        saveEntries()
        onEntryAdded?(entry)

        syncProfileWeightToLatest()

        // Detect goal-weight crossing — fire only on the transition, not on every weight past goal.
        if let profile = UserProfile.load(), let goalKg = profile.goalWeightKg, let previous = previousLatest {
            let crossed: Bool
            switch profile.goal {
            case .lose:    crossed = previous.weightKg > goalKg && entry.weightKg <= goalKg
            case .gain:    crossed = previous.weightKg < goalKg && entry.weightKg >= goalKg
            case .maintain: crossed = false
            }
            if crossed {
                NotificationCenter.default.post(name: .weightGoalReached, object: nil)
            }
        }
    }

    func deleteEntry(_ entry: WeightEntry) {
        guard !isPersistenceBlocked else { return }
        let id = entry.id
        entries.removeAll { $0.id == id }
        saveEntries()
        onEntryDeleted?(id)
        syncProfileWeightToLatest()
    }

    /// Keep UserProfile.weightKg aligned with the most recent weight entry so Settings (Weight row)
    /// and Progress (Current badge) never disagree. If the store is empty, leave the profile as-is
    /// — we still need some weightKg for BMR/TDEE math; user can log a new one.
    private func syncProfileWeightToLatest() {
        guard var profile = UserProfile.load(),
              let newest = entries.sorted(by: { $0.date > $1.date }).first else { return }
        if abs(profile.weightKg - newest.weightKg) > 0.01 {
            profile.weightKg = newest.weightKg
            profile.save()
        }
    }

    func reloadFromDefaults() {
        loadEntries(isInitialLoad: false)
    }

    func replaceAllEntries(_ newEntries: [WeightEntry]) {
        guard !isPersistenceBlocked else { return }
        entries = newEntries
        saveEntries()
    }

    /// Bulk-import weight samples discovered from HealthKit (e.g. years of
    /// scale history that predate Fud AI). Bypasses onEntryAdded so the
    /// imported externals don't echo back to HK as fresh writes — these
    /// samples already exist there. Saves + syncs profile once at the end.
    /// Samples whose id is already in the store (a restore that raced a
    /// live observer write, or the same batch delivered twice) are skipped so
    /// the list never accumulates duplicate ids.
    func importExternalEntries(_ external: [WeightEntry]) {
        guard !isPersistenceBlocked else { return }
        var seen = Set(entries.map(\.id))
        let fresh = external.filter { seen.insert($0.id).inserted }
        guard !fresh.isEmpty else { return }
        entries.append(contentsOf: fresh)
        saveEntries()
        syncProfileWeightToLatest()
    }

    /// Upserts by id; duplicate ids resolve newest-wins instead of trapping.
    func mergeWithCloudEntries(_ cloudEntries: [WeightEntry]) {
        guard !isPersistenceBlocked else { return }
        entries = Self.mergingByID(entries, with: cloudEntries)
        saveEntries()
    }

    static func mergingByID(_ existing: [WeightEntry], with incoming: [WeightEntry]) -> [WeightEntry] {
        var order: [UUID] = []
        var byID: [UUID: WeightEntry] = [:]
        for entry in existing + incoming {
            if byID.updateValue(entry, forKey: entry.id) == nil {
                order.append(entry.id)
            }
        }
        return order.compactMap { byID[$0] }
    }

    private func saveEntries() {
        entriesBlob.save(entries)
    }

    private func loadEntries(isInitialLoad: Bool) {
        switch entriesBlob.loadList(WeightEntry.self) {
        case .missing:
            entries = []
        case .decoded(let decoded, _):
            entries = decoded
        case .corrupt:
            // Backed up (or write-blocked until it is). Keep the in-memory
            // copy on reloads instead of presenting an empty history.
            if isInitialLoad { entries = [] }
        }
    }

    private func startObservingExternalChanges() {
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            UnsafeRawPointer(Unmanaged.passUnretained(self).toOpaque()),
            { _, observer, _, _, _ in
                guard let observer else { return }
                let store = Unmanaged<WeightStore>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async {
                    store.reloadFromExternalChange()
                }
            },
            Self.externalChangeNotification as CFString,
            nil,
            .deliverImmediately
        )
    }

    private func reloadFromExternalChange() {
        loadEntries(isInitialLoad: false)
        syncProfileWeightToLatest()
    }

    static func postExternalChangeNotification() {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(),
            CFNotificationName(externalChangeNotification as CFString),
            nil,
            nil,
            true
        )
    }
}
