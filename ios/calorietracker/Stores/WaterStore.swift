import Foundation

enum WaterSettings {
    static let enabledKey = "waterTrackingEnabled"
    static let dailyGoalKey = "waterDailyGoalMl"
    static let unitKey = "waterUnit"
    static let reminderEnabledKey = "waterReminderEnabled"
    static let reminderHourKey = "waterReminderHour"
    static let reminderMinuteKey = "waterReminderMinute"
    static let entriesKey = "waterEntries"

    static let defaultDailyGoalMl = 2_000
    static let dailyGoalOptions = [1_500, 2_000, 2_500, 3_000, 3_500, 4_000]
}

enum WaterUnit: String, CaseIterable, Identifiable {
    case milliliters = "ml"
    case fluidOunces = "floz"

    static let defaultUnit = WaterUnit.milliliters
    static let millilitersPerFluidOunce = 29.5735295625

    var id: String { rawValue }
    var title: String { self == .milliliters ? "Milliliters" : "Fluid Ounces" }
    var symbol: String { self == .milliliters ? "ml" : "fl oz" }
    var accessibilityName: String { self == .milliliters ? "milliliters" : "fluid ounces" }

    func displayAmount(forMilliliters milliliters: Int) -> Double {
        self == .milliliters
            ? Double(milliliters)
            : Double(milliliters) / Self.millilitersPerFluidOunce
    }

    func displayValue(forMilliliters milliliters: Int) -> String {
        if self == .milliliters { return milliliters.formatted() }
        let ounces = displayAmount(forMilliliters: milliliters)
        if abs(ounces.rounded() - ounces) < 0.05 { return Int(ounces.rounded()).formatted() }
        return ounces.formatted(.number.precision(.fractionLength(1)))
    }

    func formatted(milliliters: Int) -> String {
        "\(displayValue(forMilliliters: milliliters)) \(symbol)"
    }

    func milliliters(fromDisplayedValue value: Double) -> Int {
        let converted = self == .milliliters ? value : value * Self.millilitersPerFluidOunce
        return max(1, Int(converted.rounded()))
    }
}

struct WaterEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let date: Date
    let milliliters: Int

    init(id: UUID = UUID(), date: Date = .now, milliliters: Int) {
        self.id = id
        self.date = date
        self.milliliters = milliliters
    }
}

@Observable
final class WaterStore {
    private(set) var entries: [WaterEntry] = []
    var onEntriesChanged: (() -> Void)?
    private let entriesBlob: PersistedBlobGuard

    /// True while an unreadable blob is on disk without a backup copy.
    var isPersistenceBlocked: Bool { entriesBlob.isWriteBlocked }

    init(defaults: UserDefaults = .standard, corruptBackupDirectory: URL? = nil) {
        self.entriesBlob = PersistedBlobGuard(
            defaults: defaults,
            key: WaterSettings.entriesKey,
            backupDirectory: corruptBackupDirectory
        )
        load(isInitialLoad: true)
    }

    private func load(isInitialLoad: Bool) {
        switch entriesBlob.loadList(WaterEntry.self) {
        case .missing:
            entries = []
        case .decoded(let decoded, _):
            entries = decoded
        case .corrupt:
            if isInitialLoad { entries = [] }
        }
    }

    @discardableResult
    func add(milliliters: Int, on date: Date) -> WaterEntry? {
        add(id: UUID(), milliliters: milliliters, on: date)
    }

    /// The Watch request UUID becomes the entry UUID so a retried connectivity
    /// transfer can never add the same glass twice.
    @discardableResult
    func add(id: UUID, milliliters: Int, on date: Date) -> WaterEntry? {
        guard milliliters > 0 else { return nil }
        guard !isPersistenceBlocked else { return nil }
        if let existing = entries.first(where: { $0.id == id }) { return existing }
        let entry = WaterEntry(id: id, date: date, milliliters: milliliters)
        entries.append(entry)
        save()
        onEntriesChanged?()
        return entry
    }

    func delete(id: UUID) {
        guard !isPersistenceBlocked else { return }
        entries.removeAll { $0.id == id }
        save()
        onEntriesChanged?()
    }

    func reloadFromDefaults() {
        load(isInitialLoad: false)
        onEntriesChanged?()
    }

    func replaceEntriesFromImport(_ imported: [WaterEntry]) {
        guard !isPersistenceBlocked else { return }
        entries = imported
        save()
        onEntriesChanged?()
    }

    func clear() {
        guard entriesBlob.remove() else { return }
        entries = []
        onEntriesChanged?()
    }

    func entries(on date: Date) -> [WaterEntry] {
        entries
            .filter { Calendar.current.isDate($0.date, inSameDayAs: date) }
            .sorted { $0.date > $1.date }
    }

    func total(on date: Date) -> Int {
        entries(on: date)
            .reduce(0) { $0 + $1.milliliters }
    }

    private func save() {
        entriesBlob.save(entries)
    }
}
