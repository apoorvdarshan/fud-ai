import Foundation

/// Leaf actions available in the Home + food menu (excluding water and fasting).
enum FoodLogMethod: String, CaseIterable, Codable, Identifiable, Sendable {
    case camera
    case photos
    case barcode
    case text
    case voice
    case manual
    case siriPhrases = "siri_phrases"
    case recent
    case frequent
    case favorites
    case copyFromDay = "copy_from_day"

    var id: String { rawValue }

    /// Methods that can appear in the + menu editor and widget picker.
    static var addMenuCases: [FoodLogMethod] {
        allCases.filter { $0 != .siriPhrases || FoodLogMethod.supportsSiriPhrases }
    }

    static var supportsSiriPhrases: Bool { true }

    var title: String {
        switch self {
        case .camera: String(localized: "Camera", comment: "Add menu logging method")
        case .photos: String(localized: "Photos", comment: "Add menu logging method")
        case .barcode: String(localized: "Barcode", comment: "Add menu logging method")
        case .text: String(localized: "Text Input", comment: "Add menu logging method")
        case .voice: String(localized: "Voice", comment: "Add menu logging method")
        case .manual: String(localized: "Manual Entry", comment: "Add menu logging method")
        case .siriPhrases: String(localized: "Siri Phrases", comment: "Add menu logging method")
        case .recent: String(localized: "Recent", comment: "Add menu logging method")
        case .frequent: String(localized: "Frequent", comment: "Add menu logging method")
        case .favorites: String(localized: "Favorites", comment: "Add menu logging method")
        case .copyFromDay: String(localized: "Copy from Day", comment: "Add menu logging method")
        }
    }

    var systemImageName: String {
        switch self {
        case .camera: "camera.fill"
        case .photos: "photo.on.rectangle"
        case .barcode: "barcode.viewfinder"
        case .text: "character.cursor.ibeam"
        case .voice: "mic.fill"
        case .manual: "square.and.pencil"
        case .siriPhrases: "waveform.circle.fill"
        case .recent: "clock.fill"
        case .frequent: "repeat"
        case .favorites: "heart.fill"
        case .copyFromDay: "calendar"
        }
    }

    var quickAction: QuickAction? {
        switch self {
        case .camera: .camera
        case .photos: .photos
        case .barcode: .barcode
        case .text: .text
        case .voice: .voice
        case .manual: .manual
        case .recent: .recent
        case .frequent: .frequent
        case .favorites: .favorites
        case .siriPhrases, .copyFromDay: nil
        }
    }

    init?(quickAction: QuickAction) {
        switch quickAction {
        case .camera: self = .camera
        case .photos: self = .photos
        case .barcode: self = .barcode
        case .text: self = .text
        case .voice: self = .voice
        case .manual: self = .manual
        case .recent: self = .recent
        case .frequent: self = .frequent
        case .favorites: self = .favorites
        case .fasting: return nil
        }
    }
}

extension Notification.Name {
    static let foodLogMethodRequested = Notification.Name("FudAI.foodLogMethodRequested")
}

enum FoodLogMethodCoordinator {
    private static let pendingKey = "foodLogMethod.pending"

    @MainActor
    static func request(_ method: FoodLogMethod) {
        if let quickAction = method.quickAction {
            QuickActionCoordinator.request(quickAction)
            return
        }
        UserDefaults.standard.set(method.rawValue, forKey: pendingKey)
        NotificationCenter.default.post(name: .foodLogMethodRequested, object: method)
    }

    static func consumePending() -> FoodLogMethod? {
        let store = UserDefaults.standard
        defer { store.removeObject(forKey: pendingKey) }
        return store.string(forKey: pendingKey).flatMap(FoodLogMethod.init(rawValue:))
    }

    static func deepLinkURL(for method: FoodLogMethod) -> URL? {
        URL(string: "fudai://log-food?method=\(method.rawValue)")
    }
}
