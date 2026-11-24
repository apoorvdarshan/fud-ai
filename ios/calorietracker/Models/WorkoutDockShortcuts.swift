import Foundation

enum WorkoutDockAction: String, CaseIterable {
    case voice, saved, walking, running, text, routine, copy

    var title: LocalizedStringResource {
        switch self {
        case .voice: "Voice"
        case .saved: "Saved"
        case .walking: "Walking"
        case .running: "Running"
        case .text: "Text"
        case .routine: "Start from routine"
        case .copy: "Copy from day"
        }
    }

    var icon: String {
        switch self {
        case .voice: "mic.fill"
        case .saved: "bookmark.fill"
        case .walking: "figure.walk"
        case .running: "figure.run"
        case .text: "text.bubble"
        case .routine: "list.bullet.rectangle.portrait"
        case .copy: "calendar.badge.plus"
        }
    }
}

struct WorkoutDockShortcuts {
    static let storageKey = "workout.dockShortcuts"
    static let defaults: [WorkoutDockAction] = [.voice, .saved, .walking, .running]
    static let side: CGFloat = 52
    static let gap: CGFloat = 6
    static let addSide: CGFloat = 60

    private(set) var actions: [WorkoutDockAction]

    init(stored: String) {
        let keys = stored.split(separator: ",", omittingEmptySubsequences: false)
        let actions = keys.compactMap { WorkoutDockAction(rawValue: String($0)) }
        self.actions = keys.count == Self.defaults.count && Set(actions).count == Self.defaults.count
            ? actions : Self.defaults
    }

    mutating func assign(_ action: WorkoutDockAction, to slot: Int) {
        guard actions.indices.contains(slot) else { return }
        if let other = actions.firstIndex(of: action) {
            actions.swapAt(slot, other)
        } else {
            actions[slot] = action
        }
    }

    var stored: String { actions.map(\.rawValue).joined(separator: ",") }
}
