import Foundation
import UIKit

// MARK: - Dependencies

/// The app data the assistant reads and changes.
struct VoiceAssistantStores {
    let food: FoodStore
    let water: WaterStore
    let fasting: FastingStore
    let weight: WeightStore
    let bodyFat: BodyFatStore
    let workouts: StrengthWorkoutStore
    /// The current profile and a way to save it (goal edits).
    let profile: () -> UserProfile
    let saveProfile: (UserProfile) -> Void
}

/// The outcome of waiting for the user (photo picker, barcode scanner).
enum VoiceAssistantWait<Value> {
    case value(Value)
    case cancelled
    case timeout
    case denied
    case unavailable
}

/// What the executor needs from the assistant's UI and the app's navigation.
@MainActor
protocol VoiceAssistantToolHost: AnyObject {
    /// How this session's model sees the camera.
    var cameraFrames: LiveCameraFrames { get }
    /// The open camera's mode, or nil when the camera is closed.
    var cameraMode: VoiceAssistantCamera.Mode? { get }
    var sessionProvider: LiveProvider? { get }
    var currentScreen: VoiceAssistantScreen { get }
    var currentSettingsSection: ProfileSettingsCategory? { get }
    var homeDiaryDate: Date { get }
    var workoutDiaryDate: Date { get }

    func open(_ screen: VoiceAssistantScreen, settingsSection: ProfileSettingsCategory?)
    func showDiary(on day: Date)
    func openFoodEntry(_ entry: FoodEntry)
    func openCamera(_ mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult
    func closeCamera()
    /// A full-size photo; closes only this capture's camera before returning.
    func captureStill() async -> UIImage?
    func pickPhotos(limit: Int, timeout: TimeInterval) async -> VoiceAssistantWait<[UIImage]>
    func scanBarcode(timeout: TimeInterval) async -> VoiceAssistantWait<String>
    /// Ends the session once the goodbye has played.
    func endConversation()
}

/// The app's food and workout pipelines, so tests can stand in for the AI.
@MainActor
protocol VoiceAssistantAnalyzing {
    func analyzeImages(_ images: [UIImage], note: String?) async throws -> GeminiService.FoodAnalysis
    func analyzeText(_ description: String) async throws -> GeminiService.FoodAnalysis
    /// Open Food Facts, then the label saved for the code (#485), then USDA (#533).
    func lookUpBarcode(
        _ code: String,
        savedLabelEntries: [FoodEntry]
    ) async throws -> (analysis: GeminiService.FoodAnalysis, image: UIImage?, source: FoodSource)
    func parseWorkout(_ description: String, date: Date, unit: WeightUnit, library: [ExerciseLibraryItem]) async throws -> WorkoutTextDraft
}

/// The same calls the camera, text and barcode flows on Home make, so the user's
/// AI settings apply and hosted analysis spends hosted actions as usual.
struct VoiceAssistantAnalyzer: VoiceAssistantAnalyzing {
    /// Tests stub the barcode hosts; the app uses the shared session.
    var session: URLSession = .shared

    func analyzeImages(_ images: [UIImage], note: String?) async throws -> GeminiService.FoodAnalysis {
        try await GeminiService.analyzeFood(images: images, description: note)
    }

    func analyzeText(_ description: String) async throws -> GeminiService.FoodAnalysis {
        try await GeminiService.analyzeTextInput(description: description)
    }

    func lookUpBarcode(
        _ code: String,
        savedLabelEntries: [FoodEntry]
    ) async throws -> (analysis: GeminiService.FoodAnalysis, image: UIImage?, source: FoodSource) {
        let outcome = try await BarcodeProductLookup.lookup(
            barcode: code,
            savedLabelEntries: savedLabelEntries,
            usdaAPIKey: FoodDataCentralSettings.apiKey,
            session: session
        )
        switch outcome {
        case .product(let lookup):
            return (lookup.analysis, lookup.productImageData.flatMap(UIImage.init(data:)), .barcode)
        case .savedLabel:
            // Logged as a label again, like Home, so the barcode keeps its saved label.
            return (outcome.analysis, nil, .nutritionLabel)
        }
    }

    func parseWorkout(_ description: String, date: Date, unit: WeightUnit, library: [ExerciseLibraryItem]) async throws -> WorkoutTextDraft {
        try await GeminiService.analyzeWorkout(description: description, date: date, unit: unit, library: library)
    }
}

// MARK: - Executor

/// Runs the assistant's tools against the real app: the catalog in
/// `VoiceAssistantTools.json` plus Coach's data tools. Results follow the
/// contract: `{"ok": true, ...}` or `{"ok": false, "error": code, "message": text}`.
@MainActor
final class VoiceAssistantToolExecutor {
    enum ErrorCode: String {
        case invalidArguments = "invalid_arguments"
        case notFound = "not_found"
        case unsupported
        case cancelled
        case timeout
        case cameraUnavailable = "camera_unavailable"
        case permissionDenied = "permission_denied"
        case analysisFailed = "analysis_failed"
        case quotaExceeded = "quota_exceeded"
        case confirmationRequired = "confirmation_required"
        case workoutDiaryOff = "workout_diary_off"
    }

    /// Writes can be undone for this long.
    static let undoDuration: TimeInterval = 8
    /// Tools that wait for the user give up after this long.
    static let waitTimeout: TimeInterval = 90
    static let maximumPickedPhotos = 4

    /// Every catalog tool this executor runs; the catalog parity test checks it.
    static let handledToolNames: Set<String> = [
        "get_app_state", "open_screen", "set_diary_date", "open_food_entry", "open_camera",
        "capture_photo", "close_camera", "pick_photos", "scan_barcode", "describe_food",
        "search_foods", "log_food", "update_food_entry", "delete_food_entry", "log_water",
        "start_fast", "end_fast", "log_weight", "log_body_fat", "describe_workout",
        "log_workout", "log_cardio", "get_settings", "update_setting", "end_conversation",
    ]

    struct FoodDraft {
        let id: String
        let analysis: GeminiService.FoodAnalysis
        let images: [UIImage]
        let source: FoodSource
    }

    struct WorkoutDraft {
        let id: String
        let draft: WorkoutTextDraft
        let estimatedCalories: Int?
    }

    private struct PendingCall {
        let name: String
        let arguments: [String: Any]
        let summary: String
        let foodVersion: FoodEntry?
    }

    private struct UndoAction {
        let summary: String
        let revert: () -> Bool
    }

    /// A change a write tool is about to make.
    private struct Change {
        /// English, for the model.
        let summary: String
        /// The pending change on its confirmation card, in the user's language.
        let pendingText: String
        /// The finished change on its Undo card.
        let doneText: String
        let apply: () -> ChangeResult
    }

    private enum ChangeResult {
        case done([String: Any], revert: (() -> Bool)?)
        case failed([String: Any])
    }

    let specs: [VoiceAssistantToolSpec]
    let stores: VoiceAssistantStores
    let settings: VoiceAssistantSettings
    /// Where the app's own settings live (theme, appearance, units, water goal).
    let appDefaults: UserDefaults
    /// Cards show calories in the user's energy unit; tool arguments and
    /// results (and the model-facing summaries) stay kcal.
    private var energyUnit: EnergyUnit { EnergyUnit.current(in: appDefaults) }
    let cards: VoiceAssistantCardStore
    let analyzer: any VoiceAssistantAnalyzing
    let workoutDiaryEnabled: Bool
    weak var host: (any VoiceAssistantToolHost)?
    private let now: () -> Date
    private let calendar: Calendar

    private(set) var foodDrafts: [String: FoodDraft] = [:]
    private(set) var workoutDrafts: [String: WorkoutDraft] = [:]
    private var draftCounter = 0
    private var loggedDraftResults: [String: [String: Any]] = [:]
    private var pendingConfirmations: [UUID: PendingCall] = [:]
    private var undoActions: [UUID: UndoAction] = [:]
    private var isReset = false

    private var isCancelled: Bool { isReset || Task.isCancelled }

    init(
        specs: [VoiceAssistantToolSpec]? = nil,
        stores: VoiceAssistantStores,
        settings: VoiceAssistantSettings = VoiceAssistantSettings(),
        appDefaults: UserDefaults = .standard,
        cards: VoiceAssistantCardStore,
        analyzer: (any VoiceAssistantAnalyzing)? = nil,
        workoutDiaryEnabled: Bool,
        host: (any VoiceAssistantToolHost)?,
        now: @escaping () -> Date = Date.init,
        calendar: Calendar = .current
    ) {
        self.specs = specs ?? VoiceAssistantToolCatalog.bundled
        self.stores = stores
        self.settings = settings
        self.appDefaults = appDefaults
        self.cards = cards
        self.analyzer = analyzer ?? VoiceAssistantAnalyzer()
        self.workoutDiaryEnabled = workoutDiaryEnabled
        self.host = host
        self.now = now
        self.calendar = calendar
        cards.onExpire = { [weak self] id in
            self?.undoActions.removeValue(forKey: id)
        }
    }

    // MARK: - Entry points

    func execute(_ call: LiveToolCall) async -> [String: Any] {
        await execute(name: call.name, arguments: call.arguments)
    }

    func execute(name: String, arguments: [String: Any]) async -> [String: Any] {
        guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
        if CoachTools.nutritionToolNames.contains(name) || CoachTools.workoutToolNames.contains(name) {
            return coachTool(name, arguments: arguments)
        }
        let spec = specs.first { $0.name == name }
        if spec?.requiresWorkoutDiary == true, !workoutDiaryEnabled {
            return Self.failure(.workoutDiaryOff, "The workout diary is turned off.")
        }
        switch name {
        case "get_app_state": return appState()
        case "open_screen": return openScreen(arguments)
        case "set_diary_date": return setDiaryDate(arguments)
        case "open_food_entry": return openFoodEntry(arguments)
        case "open_camera": return await openCamera(arguments)
        case "capture_photo": return await capturePhoto(arguments)
        case "close_camera": return closeCamera()
        case "pick_photos": return await pickPhotos(arguments)
        case "scan_barcode": return await scanBarcode()
        case "describe_food": return await describeFood(arguments)
        case "search_foods": return searchFoods(arguments)
        case "log_food": return logFood(spec, arguments)
        case "update_food_entry": return updateFoodEntry(spec, arguments)
        case "delete_food_entry": return deleteFoodEntry(spec, arguments)
        case "log_water": return logWater(spec, arguments)
        case "start_fast": return startFast(spec, arguments)
        case "end_fast": return endFast(spec, arguments)
        case "log_weight": return logWeight(spec, arguments)
        case "log_body_fat": return logBodyFat(spec, arguments)
        case "describe_workout": return await describeWorkout(arguments)
        case "log_workout": return logWorkout(spec, arguments)
        case "log_cardio": return logCardio(spec, arguments)
        case "get_settings": return currentSettings()
        case "update_setting": return updateSetting(spec, arguments)
        case "end_conversation":
            host?.endConversation()
            return ["ok": true]
        default:
            return Self.failure(.unsupported, "Unknown tool \(name).")
        }
    }

    // MARK: - Card taps

    /// The user tapped Confirm: make the change and return a line for the model.
    func confirm(cardID: UUID) async -> String? {
        guard let pending = pendingConfirmations.removeValue(forKey: cardID) else { return nil }
        cards.remove(id: cardID)
        if let failure = confirmationFailure(pending) {
            return "[App] The confirmation could not be applied. Result: \(Self.json(failure))"
        }
        var arguments = pending.arguments
        arguments["user_confirmed"] = true
        let result = await execute(name: pending.name, arguments: arguments)
        return "[App] The user tapped Confirm on the card for \(pending.name) (\(pending.summary)). Result: \(Self.json(result))"
    }

    /// The user tapped Cancel on a confirmation card.
    func cancel(cardID: UUID) -> String? {
        guard let pending = pendingConfirmations.removeValue(forKey: cardID) else { return nil }
        cards.remove(id: cardID)
        return "[App] The user tapped Cancel on the card for \(pending.name) (\(pending.summary)). Nothing changed."
    }

    /// The user tapped Undo.
    func undo(cardID: UUID) -> String? {
        guard let action = undoActions.removeValue(forKey: cardID) else { return nil }
        cards.remove(id: cardID)
        let reverted = action.revert()
        return reverted
            ? "[App] The user tapped Undo: \(action.summary) was reverted."
            : "[App] The user tapped Undo, but \(action.summary) could not be reverted."
    }

    /// The user tapped Log on a draft card; the tap is their consent.
    func logDraft(_ draftID: String) async -> String? {
        let name: String
        if foodDrafts[draftID] != nil {
            name = "log_food"
        } else if workoutDrafts[draftID] != nil {
            name = "log_workout"
        } else {
            return nil
        }
        let result = await execute(name: name, arguments: ["draft_id": draftID, "user_confirmed": true])
        return "[App] The user tapped Log on draft \(draftID). Result: \(Self.json(result))"
    }

    /// Forgets everything that only lives for the session.
    func reset() {
        isReset = true
        foodDrafts.removeAll()
        workoutDrafts.removeAll()
        loggedDraftResults.removeAll()
        pendingConfirmations.removeAll()
        undoActions.removeAll()
        cards.clear()
    }

    // MARK: - Write flow

    private func perform(_ spec: VoiceAssistantToolSpec?, name: String, arguments: [String: Any], change: Change) -> [String: Any] {
        guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
        let confirmed = VoiceAssistantParsing.bool(arguments["user_confirmed"]) == true
        if confirmed, let pending = pendingConfirmations.values.first(where: {
            $0.name == name && ($0.arguments["entry_id"] as? String) == (arguments["entry_id"] as? String)
        }), let failure = confirmationFailure(pending) {
            removePendingConfirmations(forTool: name)
            return failure
        }
        let rule = spec?.confirm ?? .never
        let needsConfirmation = !confirmed && (rule == .always || (rule == .setting && settings.confirmSaves))
        if needsConfirmation {
            removePendingConfirmations(forTool: name)
            let card = VoiceAssistantCard(content: .confirmation(.init(toolName: name, text: change.pendingText)))
            let foodVersion = ["update_food_entry", "delete_food_entry"].contains(name) ? foodEntry(arguments["entry_id"]) : nil
            pendingConfirmations[card.id] = PendingCall(name: name, arguments: arguments, summary: change.summary, foodVersion: foodVersion)
            cards.show(card)
            return [
                "ok": false,
                "error": ErrorCode.confirmationRequired.rawValue,
                "message": "Ask the user to confirm this change.",
                "summary": change.summary,
            ]
        }
        removePendingConfirmations(forTool: name)
        switch change.apply() {
        case .failed(let payload):
            return payload
        case .done(var payload, let revert):
            payload["ok"] = true
            payload["summary"] = change.summary
            if let revert {
                let card = VoiceAssistantCard(content: .undo(.init(
                    text: change.doneText,
                    expiresAt: now().addingTimeInterval(Self.undoDuration)
                )))
                undoActions[card.id] = UndoAction(summary: change.summary, revert: revert)
                cards.show(card)
                payload["undo_seconds"] = Int(Self.undoDuration)
            }
            return payload
        }
    }

    private func confirmationFailure(_ pending: PendingCall) -> [String: Any]? {
        guard let expected = pending.foodVersion else { return nil }
        guard let current = stores.food.entries.first(where: { $0.id == expected.id }) else {
            return Self.failure(.notFound, "That food is no longer in the diary.")
        }
        guard Self.sameFoodVersion(current, expected) else {
            return Self.failure(.unsupported, "That food changed after the confirmation was shown. Ask again using its current details.")
        }
        return nil
    }

    private func removePendingConfirmations(forTool name: String) {
        for (id, pending) in pendingConfirmations where pending.name == name {
            pendingConfirmations.removeValue(forKey: id)
            cards.remove(id: id)
        }
    }

    // MARK: - App state and navigation

    private func appState() -> [String: Any] {
        let today = now()
        let diaryDay = host?.homeDiaryDate ?? today
        var payload: [String: Any] = [
            "ok": true,
            "screen": (host?.currentScreen ?? .home).rawValue,
            "diary_date": iso(diaryDay),
            "today": iso(today),
            "time": VoiceAssistantParsing.clockTime(today, calendar: calendar),
            "camera": [
                "open": host?.cameraMode != nil,
                "mode": (host?.cameraMode?.rawValue).map { $0 as Any } ?? NSNull(),
            ],
            "camera_frames": (host?.cameraFrames ?? .none).rawValue,
            "auto_capture": settings.autoCapture,
            "confirm_saves": settings.confirmSaves,
            "units": unitSystem,
            "weight_unit": weightUnitIsMetric ? "kg" : "lb",
            "water_unit": waterUnit == .fluidOunces ? "fl oz" : "ml",
            "today_totals": totals(on: today),
            "goals": goals(),
            "water_tracking": appDefaults.bool(forKey: WaterSettings.enabledKey),
            "fasting": fastingState(),
            "workout_diary": workoutDiaryEnabled,
            "workout_diary_date": iso(host?.workoutDiaryDate ?? today),
        ]
        if let section = host?.currentSettingsSection?.voiceAssistantSection, host?.currentScreen == .settings {
            payload["settings_section"] = section
        }
        let hiddenTabs = TabBarSettings.hiddenTabs(in: appDefaults)
        if !hiddenTabs.isEmpty {
            // open_screen can't show these until the user turns them back on.
            payload["hidden_screens"] = HideableTab.allCases.filter(hiddenTabs.contains).map(\.rawValue)
        }
        if !calendar.isDate(diaryDay, inSameDayAs: today) {
            payload["diary_totals"] = totals(on: diaryDay)
        }
        if let provider = host?.sessionProvider {
            payload["provider"] = provider.rawValue
        }
        return payload
    }

    private func openScreen(_ arguments: [String: Any]) -> [String: Any] {
        guard let screen = VoiceAssistantParsing.string(arguments["screen"]).flatMap(VoiceAssistantScreen.init(rawValue:)) else {
            return Self.failure(.invalidArguments, "screen must be home, progress, coach, workouts or settings.")
        }
        var section: ProfileSettingsCategory?
        if let raw = VoiceAssistantParsing.string(arguments["settings_section"]) {
            guard screen == .settings else {
                return Self.failure(.invalidArguments, "settings_section only works with screen=settings.")
            }
            guard let parsed = ProfileSettingsCategory(voiceAssistantSection: raw) else {
                return Self.failure(.invalidArguments, "Unknown settings_section \(raw).")
            }
            section = parsed
        }
        if TabBarSettings.hiddenTab(for: screen, in: TabBarSettings.hiddenTabs(in: appDefaults)) != nil {
            return Self.failure(
                .unsupported,
                "The user hid the \(screen.rawValue) tab. It can be shown again in Settings > App Settings > Tab Bar."
            )
        }
        host?.open(screen, settingsSection: section)
        var payload: [String: Any] = ["ok": true, "screen": screen.rawValue]
        if let section { payload["settings_section"] = section.voiceAssistantSection ?? "" }
        return payload
    }

    private func setDiaryDate(_ arguments: [String: Any]) -> [String: Any] {
        let day: Date
        do {
            guard let parsed = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar) else {
                return Self.failure(.invalidArguments, "date is required (yyyy-MM-dd, today or yesterday).")
            }
            day = parsed
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        guard day <= calendar.startOfDay(for: now()) else {
            return Self.failure(.invalidArguments, "The diary can't show a future day.")
        }
        host?.showDiary(on: day)
        return ["ok": true, "date": iso(day), "totals": totals(on: day)]
    }

    private func openFoodEntry(_ arguments: [String: Any]) -> [String: Any] {
        guard let entry = foodEntry(arguments["entry_id"]) else {
            return Self.failure(.notFound, "No logged food has that entry_id.")
        }
        host?.openFoodEntry(entry)
        return ["ok": true, "entry": entryPayload(entry)]
    }

    // MARK: - Camera, photos, barcode, text

    private func openCamera(_ arguments: [String: Any]) async -> [String: Any] {
        if let mode = VoiceAssistantParsing.string(arguments["mode"]), mode != "food" {
            return Self.failure(.invalidArguments, "open_camera only opens mode=food; use scan_barcode for barcodes.")
        }
        guard let host else { return Self.failure(.cameraUnavailable, "The camera isn't available.") }
        switch await host.openCamera(.food) {
        case .started:
            return ["ok": true, "mode": "food", "camera_frames": host.cameraFrames.rawValue, "auto_capture": settings.autoCapture]
        case .denied:
            return Self.failure(.permissionDenied, "Camera access is off for Fud AI. The user can turn it on in the iOS Settings app.")
        case .unavailable:
            return Self.failure(.cameraUnavailable, "The camera isn't available.")
        }
    }

    private func capturePhoto(_ arguments: [String: Any]) async -> [String: Any] {
        guard let host else { return Self.failure(.cameraUnavailable, "The camera isn't available.") }
        if host.cameraMode != .food {
            let opened = await openCamera([:])
            guard opened["ok"] as? Bool == true else { return opened }
        }
        let captured = await host.captureStill()
        guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
        guard let photo = captured else {
            return Self.failure(.cameraUnavailable, "Couldn't take a photo. Try again.")
        }
        let note = VoiceAssistantParsing.string(arguments["note"])
        do {
            let analysis = try await analyzer.analyzeImages([photo], note: note)
            var result = foodDraft(analysis, images: [photo], source: .snapFood)
            result["camera"] = "closed"
            return result
        } catch {
            return analysisFailure(error)
        }
    }

    private func closeCamera() -> [String: Any] {
        host?.closeCamera()
        return ["ok": true]
    }

    private func pickPhotos(_ arguments: [String: Any]) async -> [String: Any] {
        guard let host else { return Self.failure(.unsupported, "The photo library isn't available.") }
        let limit = appDefaults.string(forKey: "aiAccessMode") == AIMode.hosted.rawValue
            ? min(Self.maximumPickedPhotos, HostedAIConstants.maxHostedImages) : Self.maximumPickedPhotos
        switch await host.pickPhotos(limit: limit, timeout: Self.waitTimeout) {
        case .value(let images):
            guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
            guard !images.isEmpty else { return Self.failure(.cancelled, "No photos were chosen.") }
            guard images.count <= limit else {
                return Self.failure(.invalidArguments, "Choose at most \(limit) photos for the selected food-analysis provider.")
            }
            do {
                let analysis = try await analyzer.analyzeImages(images, note: VoiceAssistantParsing.string(arguments["note"]))
                return foodDraft(analysis, images: images, source: .snapFood)
            } catch {
                return analysisFailure(error)
            }
        case .cancelled:
            return Self.failure(.cancelled, "The user closed the photo picker.")
        case .timeout:
            return Self.failure(.timeout, "No photos were chosen in time.")
        case .denied:
            return Self.failure(.permissionDenied, "Photo access is off for Fud AI.")
        case .unavailable:
            return Self.failure(.unsupported, "The photo library isn't available.")
        }
    }

    private func scanBarcode() async -> [String: Any] {
        guard let host else { return Self.failure(.cameraUnavailable, "The camera isn't available.") }
        switch await host.scanBarcode(timeout: Self.waitTimeout) {
        case .value(let code):
            guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
            do {
                let lookup = try await analyzer.lookUpBarcode(code, savedLabelEntries: stores.food.entries)
                var result = foodDraft(lookup.analysis, images: lookup.image.map { [$0] } ?? [], source: lookup.source)
                result["barcode"] = code
                return result
            } catch let error as OpenFoodFactsService.LookupError {
                switch error {
                case .productNotFound, .missingNutrition:
                    return Self.failure(.notFound, "That product isn't in the barcode database. Offer to photograph the nutrition label or describe the food.", extra: ["barcode": code])
                case .invalidBarcode:
                    return Self.failure(.invalidArguments, "That barcode couldn't be read. Ask the user to try again.", extra: ["barcode": code])
                default:
                    return Self.failure(.analysisFailed, "The barcode lookup failed.", extra: ["barcode": code, "detail": error.localizedDescription])
                }
            } catch FoodDataCentralService.LookupError.invalidAPIKey {
                return Self.failure(.notFound, "That product isn't in Open Food Facts, and USDA FoodData Central rejected the API key saved in Settings > Product Information. Offer to photograph the nutrition label or describe the food.", extra: ["barcode": code])
            } catch {
                return analysisFailure(error)
            }
        case .cancelled:
            return Self.failure(.cancelled, "The user closed the scanner.")
        case .timeout:
            return Self.failure(.timeout, "No barcode was scanned in time.")
        case .denied:
            return Self.failure(.permissionDenied, "Camera access is off for Fud AI. The user can turn it on in the iOS Settings app.")
        case .unavailable:
            return Self.failure(.cameraUnavailable, "The camera isn't available.")
        }
    }

    private func describeFood(_ arguments: [String: Any]) async -> [String: Any] {
        guard let description = VoiceAssistantParsing.string(arguments["description"]) else {
            return Self.failure(.invalidArguments, "description is required.")
        }
        do {
            let analysis = try await analyzer.analyzeText(String(description.prefix(2000)))
            return foodDraft(analysis, images: [], source: .textInput)
        } catch {
            return analysisFailure(error)
        }
    }

    private func foodDraft(_ analysis: GeminiService.FoodAnalysis, images: [UIImage], source: FoodSource) -> [String: Any] {
        // An analyzer may finish after cancellation even if its transport does
        // not cooperate. It must never add cards to a later conversation.
        guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
        draftCounter += 1
        let id = "d\(draftCounter)"
        foodDrafts[id] = FoodDraft(id: id, analysis: analysis, images: images, source: source)
        let items = VoiceAssistantFoodBuilder.items(for: analysis)
        let totals = VoiceAssistantFoodBuilder.totals(for: analysis)
        let base = analysis.withIngredientMacroTotals()
        cards.show(VoiceAssistantCard(content: .draft(.init(
            draftID: id,
            isWorkout: false,
            title: base.name,
            lines: items.map { Self.itemLine($0, energyUnit: energyUnit) },
            calories: base.calories,
            macros: Self.macroLine(protein: base.protein, carbs: base.carbs, fat: base.fat),
            thumbnail: images.first
        ))))
        return [
            "ok": true,
            "draft_id": id,
            "name": base.name,
            "items": items,
            "totals": totals,
            "message": "Nothing is logged yet. Read back the items and total, then log_food with this draft_id when the user agrees.",
        ]
    }

    private static func itemLine(_ item: [String: Any], energyUnit: EnergyUnit) -> String {
        let name = item["name"] as? String ?? ""
        let serving = (item["serving"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let calories = item["calories"] as? Int ?? 0
        let energy = energyUnit.format(kcal: calories)
        return [name, serving, energy].compactMap { $0 }.joined(separator: " · ")
    }

    private static func macroLine(protein: Double, carbs: Double, fat: Double) -> String {
        "\(Int(protein.rounded()))P · \(Int(carbs.rounded()))C · \(Int(fat.rounded()))F"
    }

    private func analysisFailure(_ error: Error) -> [String: Any] {
        if isCancelled || error is CancellationError {
            return Self.failure(.cancelled, "Cancelled.")
        }
        if let quota = error as? HostedAIQuotaError {
            switch quota {
            case .quotaExceeded:
                cards.show(VoiceAssistantCard(content: .notice(.init(
                    text: String(localized: "You're out of hosted AI actions for today."),
                    action: .buyCredits
                ))))
                return Self.failure(.quotaExceeded, "The user is out of hosted AI actions for today. They can buy credits from the card on screen.")
            case .noActiveSubscription:
                cards.show(VoiceAssistantCard(content: .notice(.init(
                    text: quota.errorDescription ?? "",
                    action: .openAISettings
                ))))
                return Self.failure(.analysisFailed, "Food analysis needs a Plus/Pro plan or an API key in AI settings.")
            case .rateLimited, .notHostedMode:
                return Self.failure(.analysisFailed, "Food analysis is busy. Try again in a moment.")
            }
        }
        if let clarification = error as? WorkoutClarification {
            return Self.failure(.invalidArguments, clarification.question, extra: [
                "question": clarification.question,
                "options": clarification.options,
            ])
        }
        if let workoutError = error as? WorkoutTextError {
            return Self.failure(.invalidArguments, workoutError.localizedDescription)
        }
        return Self.failure(.analysisFailed, "The analysis failed.", extra: ["detail": GeminiService.analysisErrorMessage(error)])
    }

    // MARK: - Saved foods

    private func searchFoods(_ arguments: [String: Any]) -> [String: Any] {
        guard let query = VoiceAssistantParsing.string(arguments["query"]) else {
            return Self.failure(.invalidArguments, "query is required.")
        }
        let limit = Int(min(max(VoiceAssistantParsing.number(arguments["limit"]) ?? 8, 1), 20))
        let words = query.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map(String.init)
        func matches(_ name: String) -> Bool {
            words.allSatisfy { name.range(of: $0, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
        }

        var seen = Set<String>()
        var results: [[String: Any]] = []
        func add(_ entry: FoodEntry, id: String, source: String, count: Int? = nil) {
            guard results.count < limit, matches(entry.name), seen.insert(id).inserted else { return }
            var item: [String: Any] = [
                "saved_food_id": id,
                "name": entry.name,
                "serving": VoiceAssistantFoodBuilder.servingText(for: entry),
                "calories": entry.calories,
                "protein_g": VoiceAssistantFoodBuilder.rounded(entry.protein),
                "carbs_g": VoiceAssistantFoodBuilder.rounded(entry.carbs),
                "fat_g": VoiceAssistantFoodBuilder.rounded(entry.fat),
                "source": source,
                "last_logged": iso(entry.timestamp),
            ]
            if let count { item["times_logged"] = count }
            results.append(item)
        }
        for favorite in stores.food.favorites {
            add(favorite, id: "favorite-\(favorite.id.uuidString)", source: "favorite")
        }
        for group in stores.food.frequentGroups(days: 90, now: now()) {
            add(group.template, id: "food-\(group.template.id.uuidString)", source: "frequent", count: group.count)
        }
        // Frequent groups use name/calories for display grouping. Include every
        // row in that window so different nutrition/servings remain selectable.
        for entry in stores.food.recentEntries(days: 90, now: now()) {
            add(entry, id: "food-\(entry.id.uuidString)", source: "recent")
        }
        return ["ok": true, "query": query, "count": results.count, "foods": results]
    }

    private func savedFood(_ value: Any?) -> FoodEntry? {
        guard let id = VoiceAssistantParsing.string(value) else { return nil }
        if id.hasPrefix("favorite-"), let uuid = UUID(uuidString: String(id.dropFirst("favorite-".count))) {
            return stores.food.favorites.first { $0.id == uuid }
        }
        if id.hasPrefix("food-"), let uuid = UUID(uuidString: String(id.dropFirst("food-".count))) {
            return stores.food.entries.first { $0.id == uuid }
        }
        return nil
    }

    // MARK: - Food log

    private func logFood(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        let sources = ["draft_id", "saved_food_id", "item"].filter { arguments[$0] != nil && !(arguments[$0] is NSNull) }
        guard sources.count == 1 else {
            return Self.failure(.invalidArguments, "Give exactly one of draft_id, saved_food_id or item.")
        }
        let servings = VoiceAssistantParsing.number(arguments["servings"]) ?? 1
        guard servings > 0, servings <= 20 else {
            return Self.failure(.invalidArguments, "servings must be between 0 and 20.")
        }
        let defaultDay = host?.homeDiaryDate ?? now()
        let timestamp: Date
        do {
            let day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
            let time = try VoiceAssistantParsing.time(arguments["time"])
            timestamp = VoiceAssistantParsing.logDate(day: day, time: time, defaultDay: defaultDay, now: now(), calendar: calendar)
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd and time HH:mm (24-hour).")
        }
        if arguments["meal"] != nil, VoiceAssistantParsing.mealType(arguments["meal"]) == nil {
            return Self.failure(.invalidArguments, "meal must be breakfast, lunch, dinner or snack.")
        }
        let meal = VoiceAssistantParsing.mealType(arguments["meal"]) ?? MealScheduleSettings.mealType(for: timestamp)

        let entry: FoodEntry
        var loggedDraftID: String?
        if let draftID = VoiceAssistantParsing.string(arguments["draft_id"]) {
            if let result = loggedDraftResults[draftID] { return result }
            guard let draft = foodDrafts[draftID] else {
                return Self.failure(.notFound, "No food draft has that draft_id in this session.")
            }
            entry = VoiceAssistantFoodBuilder.entry(
                from: draft.analysis, images: draft.images, source: draft.source,
                servings: servings, timestamp: timestamp, mealType: meal
            )
            loggedDraftID = draftID
        } else if arguments["saved_food_id"] != nil {
            guard let saved = savedFood(arguments["saved_food_id"]) else {
                return Self.failure(.notFound, "No saved food has that saved_food_id.")
            }
            let copy = saved.duplicatedForLogging(at: timestamp, mealType: meal)
            entry = servings == 1 ? copy : copy.voiceAssistantCopy(factor: servings)
        } else {
            guard let item = arguments["item"] as? [String: Any],
                  let name = VoiceAssistantParsing.string(item["name"]),
                  let calories = VoiceAssistantParsing.number(item["calories"]), calories >= 0, calories <= 10_000 else {
                return Self.failure(.invalidArguments, "item needs a name and calories (0 to 10,000).")
            }
            let protein = VoiceAssistantParsing.number(item["protein_g"]) ?? 0
            let carbs = VoiceAssistantParsing.number(item["carbs_g"]) ?? 0
            let fat = VoiceAssistantParsing.number(item["fat_g"]) ?? 0
            guard [protein, carbs, fat].allSatisfy({ (0...1_000).contains($0) }) else {
                return Self.failure(.invalidArguments, "Each macro must be between 0 and 1,000 grams.")
            }
            let grams = VoiceAssistantParsing.grams(inServing: VoiceAssistantParsing.string(item["serving"]))
            entry = FoodEntry(
                name: String(name.prefix(120)),
                calories: Int((calories * servings).rounded()),
                protein: protein * servings,
                carbs: carbs * servings,
                fat: fat * servings,
                timestamp: timestamp,
                source: .manual,
                mealType: meal,
                servingSizeGrams: grams.map { $0 * servings }
            )
        }

        if FastingStore.persistedActiveSession(defaults: appDefaults) != nil || stores.fasting.activeSession != nil {
            return Self.failure(.unsupported, "A fast is running. End or cancel the fast before logging food.")
        }

        let dayText = iso(timestamp)
        let mealName = meal.displayName
        let change = Change(
            summary: "Log \(entry.name) (\(entry.calories) kcal) as \(meal.rawValue) on \(dayText) at \(VoiceAssistantParsing.clockTime(timestamp, calendar: calendar))",
            pendingText: String(localized: "Log \(entry.name) · \(energyUnit.format(kcal: entry.calories)) · \(mealName)"),
            doneText: String(localized: "Logged \(entry.name) · \(energyUnit.format(kcal: entry.calories))"),
            apply: { [self, stores, cards] in
                if host?.cameraMode == .food { host?.closeCamera() }
                guard stores.food.addEntry(entry) else {
                    return .failed(Self.failure(.unsupported, "The food couldn't be saved. End any running fast and try again."))
                }
                if let loggedDraftID { cards.markDraftLogged(loggedDraftID) }
                let logged = stores.food.entries.first { $0.id == entry.id } ?? entry
                return .done([
                    "entry_ids": [entry.id.uuidString],
                    "entry": self.entryPayload(logged),
                    "day_totals": self.totals(on: timestamp),
                ], revert: { [weak self, stores, cards] in
                    guard let current = stores.food.entries.first(where: { $0.id == entry.id }),
                          Self.sameFoodVersion(current, logged), stores.food.deleteEntry(current) else { return false }
                    if let loggedDraftID {
                        self?.loggedDraftResults.removeValue(forKey: loggedDraftID)
                        cards.markDraftLogged(loggedDraftID, logged: false)
                    }
                    return true
                })
            }
        )
        let result = perform(spec, name: "log_food", arguments: arguments, change: change)
        if result["ok"] as? Bool == true, let loggedDraftID { loggedDraftResults[loggedDraftID] = result }
        return result
    }

    private func updateFoodEntry(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let original = foodEntry(arguments["entry_id"]) else {
            return Self.failure(.notFound, "No logged food has that entry_id.")
        }
        let fieldNames = ["name", "calories", "protein_g", "carbs_g", "fat_g", "servings", "meal", "date", "time"]
        guard fieldNames.contains(where: { arguments[$0] != nil && !(arguments[$0] is NSNull) }) else {
            return Self.failure(.invalidArguments, "Give at least one field to change.")
        }

        let servings = VoiceAssistantParsing.number(arguments["servings"]) ?? 1
        guard servings > 0, servings <= 20 else {
            return Self.failure(.invalidArguments, "servings must be between 0 and 20.")
        }
        if arguments["meal"] != nil, VoiceAssistantParsing.mealType(arguments["meal"]) == nil {
            return Self.failure(.invalidArguments, "meal must be breakfast, lunch, dinner or snack.")
        }
        var timestamp: Date?
        do {
            let day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
            let time = try VoiceAssistantParsing.time(arguments["time"])
            if day != nil || time != nil {
                let clock = calendar.dateComponents([.hour, .minute], from: original.timestamp)
                let base = calendar.startOfDay(for: day ?? original.timestamp)
                let hour = time?.hour ?? clock.hour ?? 12
                let minute = time?.minute ?? clock.minute ?? 0
                timestamp = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: base)
            }
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd and time HH:mm (24-hour).")
        }

        var updated = original.voiceAssistantCopy(
            name: VoiceAssistantParsing.string(arguments["name"]).map { String($0.prefix(120)) },
            factor: servings,
            timestamp: timestamp,
            mealType: VoiceAssistantParsing.mealType(arguments["meal"])
        )
        if let calories = VoiceAssistantParsing.number(arguments["calories"]) {
            guard calories >= 0, calories <= 10_000 else { return Self.failure(.invalidArguments, "calories must be between 0 and 10,000.") }
            updated.calories = Int(calories.rounded())
        }
        for (key, keyPath) in [("protein_g", \FoodEntry.protein), ("carbs_g", \FoodEntry.carbs), ("fat_g", \FoodEntry.fat)] {
            if let grams = VoiceAssistantParsing.number(arguments[key]) {
                guard grams >= 0, grams <= 1_000 else { return Self.failure(.invalidArguments, "\(key) must be between 0 and 1,000.") }
                updated[keyPath: keyPath] = grams
            }
        }

        let change = Change(
            summary: "Update \(original.name) (\(original.calories) kcal) to \(updated.name), \(updated.calories) kcal, \(updated.mealType.rawValue), \(iso(updated.timestamp)) \(VoiceAssistantParsing.clockTime(updated.timestamp, calendar: calendar))",
            pendingText: String(localized: "Update \(original.name) · \(energyUnit.format(kcal: updated.calories))"),
            doneText: String(localized: "Updated \(updated.name) · \(energyUnit.format(kcal: updated.calories))"),
            apply: { [stores] in
                guard stores.food.entries.contains(where: { $0.id == original.id }) else {
                    return .failed(Self.failure(.notFound, "That food is no longer in the diary."))
                }
                guard stores.food.updateEntry(updated) else {
                    return .failed(Self.failure(.unsupported, "The food couldn't be updated because diary persistence is unavailable."))
                }
                let saved = stores.food.entries.first { $0.id == updated.id } ?? updated
                return .done(["entry": self.entryPayload(saved)], revert: { [stores] in
                    guard let current = stores.food.entries.first(where: { $0.id == original.id }),
                          Self.sameFoodVersion(current, saved) else { return false }
                    return stores.food.updateEntry(original)
                })
            }
        )
        return perform(spec, name: "update_food_entry", arguments: arguments, change: change)
    }

    private func deleteFoodEntry(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let entry = foodEntry(arguments["entry_id"]) else {
            return Self.failure(.notFound, "No logged food has that entry_id.")
        }
        let change = Change(
            summary: "Delete \(entry.name) (\(entry.calories) kcal) logged on \(iso(entry.timestamp)) at \(VoiceAssistantParsing.clockTime(entry.timestamp, calendar: calendar))",
            pendingText: String(localized: "Delete \(entry.name) · \(energyUnit.format(kcal: entry.calories))"),
            doneText: "",
            apply: { [stores] in
                guard let current = stores.food.entries.first(where: { $0.id == entry.id }) else {
                    return .failed(Self.failure(.notFound, "That food is no longer in the diary."))
                }
                guard stores.food.deleteEntry(current) else {
                    return .failed(Self.failure(.unsupported, "The food couldn't be deleted because diary persistence is unavailable."))
                }
                // Deletes were confirmed, so they have no Undo.
                return .done(["deleted_entry_id": entry.id.uuidString], revert: nil)
            }
        )
        return perform(spec, name: "delete_food_entry", arguments: arguments, change: change)
    }

    private func foodEntry(_ value: Any?) -> FoodEntry? {
        guard let text = VoiceAssistantParsing.string(value), let id = UUID(uuidString: text) else { return nil }
        return stores.food.entries.first { $0.id == id }
    }

    /// Undo must preserve any later user edit, including serving, notes and photos.
    private static func sameFoodVersion(_ lhs: FoodEntry, _ rhs: FoodEntry) -> Bool {
        guard lhs.imageData == rhs.imageData, lhs.additionalImageData == rhs.additionalImageData else { return false }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let left = try? encoder.encode(lhs), let right = try? encoder.encode(rhs) else { return false }
        return left == right
    }

    // MARK: - Water, fasting, body

    private func logWater(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let amount = VoiceAssistantParsing.number(arguments["amount"]),
              let unit = VoiceAssistantParsing.string(arguments["unit"]),
              let milliliters = VoiceAssistantParsing.milliliters(amount: amount, unit: unit),
              milliliters <= 10_000 else {
            return Self.failure(.invalidArguments, "Give a positive amount with unit ml, l, oz, cup or glass (up to 10 liters).")
        }
        let date: Date
        do {
            let day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
            date = VoiceAssistantParsing.logDate(day: day, time: nil, defaultDay: host?.homeDiaryDate ?? now(), now: now(), calendar: calendar)
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        let amountText = waterUnit.formatted(milliliters: milliliters)
        let change = Change(
            summary: "Log \(milliliters) ml of water on \(iso(date))",
            pendingText: String(localized: "Log \(amountText) of water"),
            doneText: String(localized: "Logged \(amountText) of water"),
            apply: { [stores, appDefaults] in
                guard let entry = stores.water.add(milliliters: milliliters, on: date) else {
                    return .failed(Self.failure(.unsupported, "The water log couldn't be saved."))
                }
                var payload: [String: Any] = [
                    "milliliters": milliliters,
                    "day_total_ml": stores.water.total(on: date),
                    "goal_ml": appDefaults.object(forKey: WaterSettings.dailyGoalKey) as? Int ?? WaterSettings.defaultDailyGoalMl,
                ]
                if !appDefaults.bool(forKey: WaterSettings.enabledKey) {
                    payload["note"] = "Water tracking is off in Settings, so Home doesn't show the water bar. The log is kept."
                }
                return .done(payload, revert: { [stores] in
                    guard stores.water.entries.contains(where: { $0.id == entry.id }) else { return false }
                    stores.water.delete(id: entry.id)
                    return true
                })
            }
        )
        return perform(spec, name: "log_water", arguments: arguments, change: change)
    }

    private func startFast(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let hours = VoiceAssistantParsing.number(arguments["goal_hours"]), hours >= 1, hours <= 168 else {
            return Self.failure(.invalidArguments, "goal_hours must be between 1 and 168.")
        }
        guard appDefaults.bool(forKey: FastingSettings.enabledKey) else {
            return Self.failure(.unsupported, "Fasting tracking is off. The user can turn it on in Settings → Tracking & Notifications.")
        }
        if let active = stores.fasting.activeSession {
            return Self.failure(.invalidArguments, "A fast is already running.", extra: ["started_at": isoTimestamp(active.startedAt)])
        }
        let minutes = Int((hours * 60).rounded())
        let goalText = FastingDurationFormatter.goal(minutes: minutes)
        let change = Change(
            summary: "Start a \(MacroValueFormatter.string(hours))-hour fast now",
            pendingText: String(localized: "Start a fast · \(goalText) goal"),
            doneText: String(localized: "Started a fast · \(goalText) goal"),
            apply: { [stores] in
                guard let session = stores.fasting.start(goalMinutes: minutes, at: self.now()) else {
                    return .failed(Self.failure(.unsupported, "The fast couldn't be started."))
                }
                return .done([
                    "started_at": self.isoTimestamp(session.startedAt),
                    "goal_hours": Double(session.goalMinutes) / 60,
                    "ends_at": self.isoTimestamp(session.goalDate),
                ], revert: { [stores] in
                    guard stores.fasting.sessions.contains(where: { $0.id == session.id }) else { return false }
                    stores.fasting.delete(id: session.id)
                    return true
                })
            }
        )
        return perform(spec, name: "start_fast", arguments: arguments, change: change)
    }

    private func endFast(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let active = stores.fasting.activeSession else {
            return Self.failure(.notFound, "No fast is running.")
        }
        let durationText = FastingDurationFormatter.compact(seconds: active.duration(at: now()))
        let change = Change(
            summary: "End the fast that started \(isoTimestamp(active.startedAt)) after \(durationText)",
            pendingText: String(localized: "End your fast · \(durationText)"),
            doneText: String(localized: "Ended your fast · \(durationText)"),
            apply: { [stores] in
                guard let completed = stores.fasting.endActive(at: self.now()) else {
                    return .failed(Self.failure(.notFound, "No fast is running."))
                }
                return .done([
                    "duration_minutes": Int(completed.duration() / 60),
                    "goal_reached": completed.duration() >= TimeInterval(completed.goalMinutes * 60),
                ], revert: { [stores] in
                    guard var session = stores.fasting.sessions.first(where: { $0.id == completed.id }),
                          stores.fasting.activeSession == nil else { return false }
                    session.endedAt = nil
                    return stores.fasting.update(session)
                })
            }
        )
        return perform(spec, name: "end_fast", arguments: arguments, change: change)
    }

    private func logWeight(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let value = VoiceAssistantParsing.number(arguments["value"]),
              let unit = VoiceAssistantParsing.string(arguments["unit"]),
              let kilograms = VoiceAssistantParsing.kilograms(value: value, unit: unit),
              (20...500).contains(kilograms) else {
            return Self.failure(.invalidArguments, "Give a weight with unit kg or lb (20 to 500 kg).")
        }
        let date: Date
        do {
            let day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
            date = VoiceAssistantParsing.logDate(day: day, time: nil, defaultDay: now(), now: now(), calendar: calendar)
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        guard date <= now().addingTimeInterval(60) else {
            return Self.failure(.invalidArguments, "A weight can't be logged for a future day.")
        }
        let weightText = Self.weightText(kilograms: kilograms, metric: weightUnitIsMetric)
        let change = Change(
            summary: "Log a weight of \(weightText) on \(iso(date))",
            pendingText: String(localized: "Log weight · \(weightText)"),
            doneText: String(localized: "Logged weight · \(weightText)"),
            apply: { [stores] in
                guard !stores.weight.isPersistenceBlocked else {
                    return .failed(Self.failure(.unsupported, "The weight couldn't be saved."))
                }
                let previousWeight = stores.profile().weightKg
                let entry = WeightEntry(date: date, weightKg: kilograms)
                stores.weight.addEntry(entry)
                return .done(["kg": VoiceAssistantFoodBuilder.rounded(kilograms), "lb": VoiceAssistantFoodBuilder.rounded(kilograms * VoiceAssistantParsing.poundsPerKilogram)], revert: { [stores] in
                    guard let current = stores.weight.entries.first(where: { $0.id == entry.id }) else { return false }
                    stores.weight.deleteEntry(current)
                    guard !stores.weight.entries.contains(where: { $0.id == entry.id }) else { return false }
                    // The store intentionally leaves the profile alone when
                    // its last reading is deleted. Undo restores that field.
                    if stores.weight.entries.isEmpty {
                        var profile = stores.profile()
                        if abs(profile.weightKg - kilograms) < 0.01 {
                            profile.weightKg = previousWeight
                            stores.saveProfile(profile)
                        }
                    }
                    return true
                })
            }
        )
        return perform(spec, name: "log_weight", arguments: arguments, change: change)
    }

    private func logBodyFat(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let percent = VoiceAssistantParsing.number(arguments["percent"]), (2...75).contains(percent) else {
            return Self.failure(.invalidArguments, "percent must be between 2 and 75.")
        }
        let date: Date
        do {
            let day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
            date = VoiceAssistantParsing.logDate(day: day, time: nil, defaultDay: now(), now: now(), calendar: calendar)
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        guard date <= now().addingTimeInterval(60) else {
            return Self.failure(.invalidArguments, "Body fat can't be logged for a future day.")
        }
        let percentText = "\(MacroValueFormatter.string(VoiceAssistantFoodBuilder.rounded(percent)))%"
        let change = Change(
            summary: "Log body fat of \(percentText) on \(iso(date))",
            pendingText: String(localized: "Log body fat · \(percentText)"),
            doneText: String(localized: "Logged body fat · \(percentText)"),
            apply: { [stores] in
                let previousBodyFat = stores.profile().bodyFatPercentage
                let entry = BodyFatEntry(date: date, bodyFatFraction: percent / 100)
                stores.bodyFat.addEntry(entry)
                return .done(["percent": VoiceAssistantFoodBuilder.rounded(percent)], revert: { [stores] in
                    guard let current = stores.bodyFat.entries.first(where: { $0.id == entry.id }) else { return false }
                    stores.bodyFat.deleteEntry(current)
                    if stores.bodyFat.entries.isEmpty {
                        var profile = stores.profile()
                        if abs((profile.bodyFatPercentage ?? -1) - entry.bodyFatFraction) < 0.0001 {
                            profile.bodyFatPercentage = previousBodyFat
                            stores.saveProfile(profile)
                        }
                    }
                    return true
                })
            }
        )
        return perform(spec, name: "log_body_fat", arguments: arguments, change: change)
    }

    // MARK: - Workouts

    private func describeWorkout(_ arguments: [String: Any]) async -> [String: Any] {
        guard let description = VoiceAssistantParsing.string(arguments["description"]) else {
            return Self.failure(.invalidArguments, "description is required.")
        }
        let day: Date
        do {
            day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar)
                ?? calendar.startOfDay(for: host?.workoutDiaryDate ?? now())
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        guard day <= calendar.startOfDay(for: now()) else {
            return Self.failure(.invalidArguments, "Workouts can be logged for today or earlier.")
        }
        let library = stores.workouts.exerciseLibrary.exercises
        do {
            let draft = try await analyzer.parseWorkout(String(description.prefix(4000)), date: day, unit: currentWeightUnit, library: library)
            return workoutDraft(draft, library: library)
        } catch {
            return analysisFailure(error)
        }
    }

    private func workoutDraft(_ draft: WorkoutTextDraft, library: [ExerciseLibraryItem]) -> [String: Any] {
        guard !isCancelled else { return Self.failure(.cancelled, "Cancelled.") }
        draftCounter += 1
        let id = "w\(draftCounter)"
        let planned = (try? draft.planned(library: library, today: now())) ?? []
        let estimate = StrengthWorkoutBurnEstimator.estimate(
            exercises: planned,
            bodyWeightKg: bodyWeightKg,
            defaultWeightUnit: currentWeightUnit,
            defaultRPEScale: stores.workouts.preferences.rpeScale
        )
        workoutDrafts[id] = WorkoutDraft(id: id, draft: draft, estimatedCalories: estimate?.calories)
        let exercises: [[String: Any]] = draft.exercises.map { exercise in
            var item: [String: Any] = ["name": exercise.name]
            if !exercise.minutes.isEmpty { item["minutes"] = Double(exercise.minutes) ?? exercise.minutes }
            if !exercise.sets.isEmpty {
                item["sets"] = exercise.sets.map { set -> [String: Any] in
                    var value: [String: Any] = [:]
                    if !set.reps.isEmpty { value["reps"] = Int(set.reps) ?? set.reps }
                    if !set.weight.isEmpty { value["weight"] = set.weight; value["unit"] = exercise.unit }
                    if !set.rpe.isEmpty { value["rpe"] = set.rpe }
                    return value
                }
            }
            return item
        }
        cards.show(VoiceAssistantCard(content: .draft(.init(
            draftID: id,
            isWorkout: true,
            title: String(localized: "Workout"),
            lines: draft.exercises.map(Self.workoutLine),
            calories: estimate?.calories,
            macros: nil,
            thumbnail: nil
        ))))
        var payload: [String: Any] = [
            "ok": true,
            "draft_id": id,
            "date": draft.date,
            "exercises": exercises,
            "message": "Nothing is saved yet. Read back the exercises, then log_workout with this draft_id when the user agrees.",
        ]
        if let estimate { payload["estimated_calories"] = estimate.calories }
        return payload
    }

    private static func workoutLine(_ exercise: WorkoutTextExercise) -> String {
        var parts = [exercise.name]
        if let minutes = Double(exercise.minutes), minutes > 0 {
            parts.append(String(localized: "\(MacroValueFormatter.string(minutes)) min"))
        }
        if !exercise.sets.isEmpty {
            let reps = exercise.sets.map(\.reps).filter { !$0.isEmpty }
            let weight = exercise.sets.first(where: { !$0.weight.isEmpty })?.weight
            var setText = reps.count == exercise.sets.count && Set(reps).count == 1
                ? "\(exercise.sets.count)×\(reps[0])"
                : "\(exercise.sets.count)×"
            if let weight { setText += " @ \(weight) \(exercise.unit)" }
            parts.append(setText)
        }
        return parts.joined(separator: " · ")
    }

    private func logWorkout(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let draftID = VoiceAssistantParsing.string(arguments["draft_id"]), let workout = workoutDrafts[draftID] else {
            return Self.failure(.notFound, "No workout draft has that draft_id in this session.")
        }
        if let result = loggedDraftResults[draftID] { return result }
        let library = stores.workouts.exerciseLibrary.exercises
        do {
            _ = try workout.draft.planned(library: library, today: now())
        } catch {
            return analysisFailure(error)
        }
        guard let day = StrengthWorkoutStore.date(for: workout.draft.date) else {
            return Self.failure(.invalidArguments, "The workout draft has an invalid date.")
        }
        let names = workout.draft.exercises.map(\.name).joined(separator: ", ")
        let change = Change(
            summary: "Add \(names) to the workout diary for \(workout.draft.date)",
            pendingText: String(localized: "Add \(names) to your workout diary"),
            doneText: String(localized: "Added \(names) to your workout diary"),
            apply: { [self, stores] in
                let previousIDs = Set(stores.workouts.exercises(for: day).map(\.id))
                let previousBurn = stores.workouts.caloriesBurned(on: day)
                do {
                    try stores.workouts.addTextWorkout(workout.draft, library: library)
                } catch {
                    return .failed(Self.failure(.unsupported, error.localizedDescription))
                }
                self.cards.markDraftLogged(draftID)
                let ids = workout.draft.exercises.map(\.id).filter { !previousIDs.contains($0) }
                return .done(["date": workout.draft.date, "exercises": workout.draft.exercises.count], revert: { [weak self, stores] in
                    let existing = Set(stores.workouts.exercises(for: day).map(\.id))
                    let added = ids.filter(existing.contains)
                    guard !added.isEmpty else { return false }
                    for id in added { stores.workouts.removeExercise(id, on: day) }
                    guard !stores.workouts.exercises(for: day).contains(where: { added.contains($0.id) }) else { return false }
                    if let previousBurn, let self {
                        stores.workouts.upsertCalculatedWorkout(on: day, caloriesBurned: previousBurn, weightUnit: self.currentWeightUnit)
                    }
                    self?.loggedDraftResults.removeValue(forKey: draftID)
                    self?.cards.markDraftLogged(draftID, logged: false)
                    return true
                })
            }
        )
        let result = perform(spec, name: "log_workout", arguments: arguments, change: change)
        if result["ok"] as? Bool == true { loggedDraftResults[draftID] = result }
        return result
    }

    /// Library exercises for common spoken activities.
    static let cardioAliases: [String: String] = [
        "run": "Running_Outdoor", "running": "Running_Outdoor", "jog": "Running_Outdoor", "jogging": "Running_Outdoor",
        "walk": "Walking_Outdoor", "walking": "Walking_Outdoor", "hike": "Trail_Running_Walking", "hiking": "Trail_Running_Walking",
        "cycling": "Bicycling", "cycle": "Bicycling", "biking": "Bicycling", "bike": "Bicycling", "bicycling": "Bicycling",
        "spinning": "Bicycling_Stationary", "stationary bike": "Bicycling_Stationary", "elliptical": "Elliptical_Trainer",
        "rowing": "Rowing_Stationary", "row": "Rowing_Stationary", "jump rope": "Rope_Jumping", "skipping": "Rope_Jumping",
        "rope jumping": "Rope_Jumping", "stairs": "Stairmaster", "stair climbing": "Stairmaster", "stairmaster": "Stairmaster",
        "skating": "Skating", "treadmill": "Running_Treadmill",
    ]

    private func logCardio(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let activity = VoiceAssistantParsing.string(arguments["activity"]).map({ String($0.prefix(80)) }) else {
            return Self.failure(.invalidArguments, "activity is required.")
        }
        guard let minutes = VoiceAssistantParsing.number(arguments["minutes"]), minutes >= 1, minutes <= 600 else {
            return Self.failure(.invalidArguments, "minutes must be between 1 and 600.")
        }
        let explicitCalories = VoiceAssistantParsing.number(arguments["calories"])
        if let explicitCalories, !(0...5_000).contains(explicitCalories) {
            return Self.failure(.invalidArguments, "calories must be between 0 and 5,000.")
        }
        let day: Date
        do {
            day = try VoiceAssistantParsing.day(arguments["date"], now: now(), calendar: calendar) ?? calendar.startOfDay(for: now())
        } catch {
            return Self.failure(.invalidArguments, "date must be yyyy-MM-dd, today or yesterday.")
        }
        guard day <= calendar.startOfDay(for: now()) else {
            return Self.failure(.invalidArguments, "Cardio can be logged for today or earlier.")
        }

        let library = stores.workouts.exerciseLibrary.exercises
        let item = Self.cardioItem(for: activity, in: library)
        let exercise = WorkoutTextExercise(
            exerciseID: item?.id,
            name: item?.name ?? activity.localizedCapitalized,
            minutes: MacroValueFormatter.string(minutes.rounded()),
            unit: currentWeightUnit.rawValue,
            intensity: "moderate",
            sets: []
        )
        let draft = WorkoutTextDraft(date: StrengthWorkoutStore.dateKey(for: day), exercises: [exercise])
        do {
            _ = try draft.planned(library: library, today: now())
        } catch {
            return analysisFailure(error)
        }
        let minutesInt = Int(minutes.rounded())
        let change = Change(
            summary: "Log \(minutesInt) minutes of \(exercise.name) on \(draft.date)",
            pendingText: String(localized: "Log \(exercise.name) · \(minutesInt) min"),
            doneText: String(localized: "Logged \(exercise.name) · \(minutesInt) min"),
            apply: { [stores] in
                let previousBurn = stores.workouts.caloriesBurned(on: day)
                do {
                    try stores.workouts.addTextWorkout(draft, library: library)
                } catch {
                    return .failed(Self.failure(.unsupported, error.localizedDescription))
                }
                let burned = self.recalculateBurn(on: day, extraCalories: explicitCalories.map { Int($0.rounded()) }, excluding: exercise.id)
                var payload: [String: Any] = ["activity": exercise.name, "minutes": minutesInt, "date": draft.date]
                if let burned { payload["day_calories_burned"] = burned }
                return .done(payload, revert: { [weak executor = self, stores] in
                    guard stores.workouts.exercises(for: day).contains(where: { $0.id == exercise.id }) else { return false }
                    stores.workouts.removeExercise(exercise.id, on: day)
                    guard !stores.workouts.exercises(for: day).contains(where: { $0.id == exercise.id }) else { return false }
                    if let previousBurn, let executor {
                        stores.workouts.upsertCalculatedWorkout(on: day, caloriesBurned: previousBurn, weightUnit: executor.currentWeightUnit)
                    }
                    return true
                })
            }
        )
        return perform(spec, name: "log_cardio", arguments: arguments, change: change)
    }

    static func cardioItem(for activity: String, in library: [ExerciseLibraryItem]) -> ExerciseLibraryItem? {
        let key = activity.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
        if let id = cardioAliases[key], let item = library.first(where: { $0.id == id }) {
            return item
        }
        let cardio = library.filter { $0.category.compare("cardio", options: .caseInsensitive) == .orderedSame }
        return cardio.first { $0.name.compare(key, options: [.caseInsensitive, .diacriticInsensitive]) == .orderedSame }
            ?? cardio.first { $0.name.range(of: key, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }

    /// Recalculates the day's burn the way the diary's quick cardio log does. With
    /// `extraCalories`, the given exercise counts as that many calories instead of
    /// the estimate. Returns the new total.
    private func recalculateBurn(on day: Date, extraCalories: Int?, excluding exerciseID: UUID?) -> Int? {
        let exercises = stores.workouts.exercises(for: day)
        let estimated = exercises.filter { extraCalories == nil || $0.id != exerciseID }
        let estimate = StrengthWorkoutBurnEstimator.estimate(
            exercises: estimated,
            bodyWeightKg: bodyWeightKg,
            defaultWeightUnit: currentWeightUnit,
            defaultRPEScale: stores.workouts.preferences.rpeScale
        )?.calories
        let total = (estimate ?? 0) + (extraCalories ?? 0)
        guard total > 0 else { return nil }
        return stores.workouts.upsertCalculatedWorkout(on: day, caloriesBurned: total, weightUnit: currentWeightUnit)?.caloriesBurned
    }

    // MARK: - Settings

    private enum SettingKey: String, CaseIterable {
        case themeColor = "theme_color"
        case appearance
        case units
        case calorieGoal = "calorie_goal"
        case proteinGoal = "protein_goal_g"
        case carbsGoal = "carbs_goal_g"
        case fatGoal = "fat_goal_g"
        case waterGoal = "water_goal_ml"
        case assistantVoice = "assistant_voice"
        case assistantAutoCapture = "assistant_auto_capture"
        case assistantConfirmSaves = "assistant_confirm_saves"

        var displayName: String {
            switch self {
            case .themeColor: String(localized: "Theme Color")
            case .appearance: String(localized: "Appearance")
            case .units: String(localized: "Units")
            case .calorieGoal: String(localized: "Calorie goal")
            case .proteinGoal: String(localized: "Protein goal")
            case .carbsGoal: String(localized: "Carbs goal")
            case .fatGoal: String(localized: "Fat goal")
            case .waterGoal: String(localized: "Water goal")
            case .assistantVoice: String(localized: "Assistant voice")
            case .assistantAutoCapture: String(localized: "Auto-capture photos")
            case .assistantConfirmSaves: String(localized: "Confirm before saving")
            }
        }
    }

    private var voiceProvider: LiveProvider {
        if let provider = host?.sessionProvider { return provider }
        if case .ready(let provider) = VoiceAssistantRouting.current(settings: settings) { return provider }
        return .gemini
    }

    private func currentSettings() -> [String: Any] {
        let profile = stores.profile()
        return [
            "ok": true,
            "settings": [
                "theme_color": appDefaults.string(forKey: AppThemeColor.storageKey) ?? AppThemeColor.defaultColor.rawValue,
                "appearance": appDefaults.string(forKey: "appearanceMode") ?? "system",
                "units": unitSystem,
                "calorie_goal": profile.effectiveCalories,
                "protein_goal_g": profile.effectiveProtein,
                "carbs_goal_g": profile.effectiveCarbs,
                "fat_goal_g": profile.effectiveFat,
                "water_goal_ml": appDefaults.object(forKey: WaterSettings.dailyGoalKey) as? Int ?? WaterSettings.defaultDailyGoalMl,
                "assistant_voice": settings.voice(for: voiceProvider),
                "assistant_auto_capture": settings.autoCapture,
                "assistant_confirm_saves": settings.confirmSaves,
            ] as [String: Any],
            "theme_colors": AppThemeColor.allCases.map(\.rawValue),
            "voices": voiceProvider.voices,
            "adaptive_goals": adaptiveGoalsEnabled,
        ]
    }

    private func updateSetting(_ spec: VoiceAssistantToolSpec?, _ arguments: [String: Any]) -> [String: Any] {
        guard let keyText = VoiceAssistantParsing.string(arguments["key"]), let key = SettingKey(rawValue: keyText) else {
            return Self.failure(.unsupported, "That setting can't be changed here.")
        }
        guard let rawValue = arguments["value"], !(rawValue is NSNull) else {
            return Self.failure(.invalidArguments, "value is required.")
        }
        // Named settings need text. Numeric settings validate rawValue below,
        // before any formatting that could convert an oversized number to Int.
        let text = VoiceAssistantParsing.string(rawValue) ?? ""

        let valueText: String
        let summaryValue: String
        let apply: () -> ChangeResult
        switch key {
        case .themeColor:
            guard let color = AppThemeColor.allCases.first(where: { $0.rawValue.caseInsensitiveCompare(text) == .orderedSame || $0.displayName.caseInsensitiveCompare(text) == .orderedSame }) else {
                return Self.failure(.invalidArguments, "Unknown theme color \(text).", extra: ["theme_colors": AppThemeColor.allCases.map(\.rawValue)])
            }
            valueText = color.displayName
            summaryValue = color.rawValue
            apply = stringSetting(AppThemeColor.storageKey, to: color.rawValue, default: AppThemeColor.defaultColor.rawValue)
        case .appearance:
            let mode = text.lowercased()
            guard ["system", "light", "dark"].contains(mode) else {
                return Self.failure(.invalidArguments, "appearance must be system, light or dark.")
            }
            valueText = mode == "light" ? String(localized: "Light") : mode == "dark" ? String(localized: "Dark") : String(localized: "System")
            summaryValue = mode
            apply = stringSetting("appearanceMode", to: mode, default: "system")
        case .units:
            let system = text.lowercased()
            guard ["metric", "imperial"].contains(system) else {
                return Self.failure(.invalidArguments, "units must be metric or imperial.")
            }
            let metric = system == "metric"
            valueText = metric ? String(localized: "Metric") : String(localized: "Imperial")
            summaryValue = system
            apply = { [appDefaults] in
                let keys = [HeightUnit.storageKey, WeightUnit.storageKey, WaterSettings.unitKey]
                let previous = keys.map { appDefaults.string(forKey: $0) }
                appDefaults.set(metric ? HeightUnit.cm.rawValue : HeightUnit.ftin.rawValue, forKey: HeightUnit.storageKey)
                appDefaults.set(metric ? WeightUnit.kg.rawValue : WeightUnit.lbs.rawValue, forKey: WeightUnit.storageKey)
                appDefaults.set(metric ? WaterUnit.milliliters.rawValue : WaterUnit.fluidOunces.rawValue, forKey: WaterSettings.unitKey)
                return .done(["key": key.rawValue, "value": system], revert: {
                    for (storageKey, value) in zip(keys, previous) {
                        if let value { appDefaults.set(value, forKey: storageKey) } else { appDefaults.removeObject(forKey: storageKey) }
                    }
                    return true
                })
            }
        case .calorieGoal, .proteinGoal, .carbsGoal, .fatGoal:
            guard let number = VoiceAssistantParsing.number(rawValue) else {
                return Self.failure(.invalidArguments, "\(key.rawValue) needs a number.")
            }
            let range = key == .calorieGoal ? 800...10_000 : 0...1_000
            guard let value = Int(exactly: number.rounded()), range.contains(value) else {
                return Self.failure(.invalidArguments, "\(key.rawValue) must be between \(range.lowerBound) and \(range.upperBound).")
            }
            guard !adaptiveGoalsEnabled else {
                return Self.failure(.unsupported, "Adaptive Goals is on and sets the targets each week. The user can turn it off in Settings → Goals & Nutrition to set their own.")
            }
            valueText = key == .calorieGoal ? energyUnit.format(kcal: value) : "\(value) g"
            summaryValue = "\(value)"
            apply = goalSetting(key, value: value)
        case .waterGoal:
            guard let number = VoiceAssistantParsing.number(rawValue), (250...10_000).contains(number) else {
                return Self.failure(.invalidArguments, "water_goal_ml must be between 250 and 10,000.")
            }
            let milliliters = Int(number.rounded())
            valueText = waterUnit.formatted(milliliters: milliliters)
            summaryValue = "\(milliliters) ml"
            apply = { [appDefaults] in
                let previous = appDefaults.object(forKey: WaterSettings.dailyGoalKey)
                appDefaults.set(milliliters, forKey: WaterSettings.dailyGoalKey)
                return .done(["key": key.rawValue, "value": milliliters], revert: {
                    if let previous { appDefaults.set(previous, forKey: WaterSettings.dailyGoalKey) } else { appDefaults.removeObject(forKey: WaterSettings.dailyGoalKey) }
                    return true
                })
            }
        case .assistantVoice:
            let provider = voiceProvider
            guard let voice = provider.voices.first(where: { $0.caseInsensitiveCompare(text) == .orderedSame }) else {
                return Self.failure(.invalidArguments, "Unknown voice \(text) for \(provider.displayName).", extra: ["voices": provider.voices])
            }
            valueText = voice.prefix(1).uppercased() + voice.dropFirst()
            summaryValue = voice
            apply = { [settings] in
                let previous = settings.voice(for: provider)
                settings.setVoice(voice, for: provider)
                return .done(["key": key.rawValue, "value": voice, "note": "The new voice is used from the next conversation."], revert: {
                    settings.setVoice(previous, for: provider)
                    return true
                })
            }
        case .assistantAutoCapture, .assistantConfirmSaves:
            guard let enabled = VoiceAssistantParsing.bool(rawValue) else {
                return Self.failure(.invalidArguments, "\(key.rawValue) needs true or false.")
            }
            valueText = enabled ? String(localized: "On") : String(localized: "Off")
            summaryValue = enabled ? "true" : "false"
            apply = { [settings] in
                let previous = key == .assistantAutoCapture ? settings.autoCapture : settings.confirmSaves
                if key == .assistantAutoCapture { settings.autoCapture = enabled } else { settings.confirmSaves = enabled }
                return .done(["key": key.rawValue, "value": enabled], revert: {
                    if key == .assistantAutoCapture { settings.autoCapture = previous } else { settings.confirmSaves = previous }
                    return true
                })
            }
        }

        let settingName = key.displayName
        let change = Change(
            summary: "Change \(key.rawValue) to \(summaryValue)",
            pendingText: String(localized: "Change \(settingName) to \(valueText)"),
            doneText: String(localized: "Changed \(settingName) to \(valueText)"),
            apply: apply
        )
        return perform(spec, name: "update_setting", arguments: arguments, change: change)
    }

    private func stringSetting(_ storageKey: String, to value: String, default defaultValue: String) -> () -> ChangeResult {
        { [appDefaults] in
            let previous = appDefaults.string(forKey: storageKey)
            appDefaults.set(value, forKey: storageKey)
            return .done(["value": value], revert: {
                appDefaults.set(previous ?? defaultValue, forKey: storageKey)
                return true
            })
        }
    }

    /// Goal edits follow Settings: the edited target is locked, and the others
    /// rebalance around it.
    private func goalSetting(_ key: SettingKey, value: Int) -> () -> ChangeResult {
        { [stores] in
            let previous = stores.profile()
            var profile = previous
            switch key {
            case .calorieGoal:
                profile.applyCaloriesEdit(value)
                profile.caloriesLocked = true
            default:
                let macro: AutoBalanceMacro = key == .proteinGoal ? .protein : key == .carbsGoal ? .carbs : .fat
                guard profile.applyMacroEdit(macro, grams: value) else {
                    return .failed(Self.failure(.invalidArguments, "The calorie goal and the other macros are locked, so this macro can't change. The user can unlock them in Settings → Goals & Nutrition."))
                }
                if !profile.isMacroLocked(macro) {
                    _ = profile.toggleMacroLock(macro)
                }
            }
            stores.saveProfile(profile)
            return .done([
                "key": key.rawValue,
                "calorie_goal": profile.effectiveCalories,
                "protein_goal_g": profile.effectiveProtein,
                "carbs_goal_g": profile.effectiveCarbs,
                "fat_goal_g": profile.effectiveFat,
            ], revert: {
                var current = stores.profile()
                // Only fields this goal edit changed belong to its Undo. Leave
                // weight, height, name and other later profile edits intact.
                func restore<Value: Equatable>(_ field: WritableKeyPath<UserProfile, Value>) -> Bool {
                    guard previous[keyPath: field] != profile[keyPath: field] else { return true }
                    guard current[keyPath: field] == profile[keyPath: field] else { return false }
                    current[keyPath: field] = previous[keyPath: field]
                    return true
                }
                guard restore(\.customCalories), restore(\.customProtein), restore(\.customCarbs),
                      restore(\.customFat), restore(\.caloriesLocked), restore(\.lockedMacros) else { return false }
                stores.saveProfile(current)
                return true
            })
        }
    }

    // MARK: - Coach data tools

    private func coachTool(_ name: String, arguments: [String: Any]) -> [String: Any] {
        if CoachTools.workoutToolNames.contains(name), !workoutDiaryEnabled {
            return Self.failure(.workoutDiaryOff, "The workout diary is turned off.")
        }
        let tools = CoachTools(
            weights: stores.weight.entries,
            bodyFats: stores.bodyFat.entries,
            foods: stores.food.entries,
            fastingSessions: stores.fasting.sessions,
            workoutSessions: stores.workouts.completedSessions,
            workoutPlans: Array(stores.workouts.dayPlans.values),
            workoutPreferences: stores.workouts.preferences,
            workoutPlanWeightUnit: currentWeightUnit,
            workoutAccessEnabled: workoutDiaryEnabled,
            exerciseLibrary: workoutDiaryEnabled ? stores.workouts.exerciseLibrary.exercises : []
        )
        let output = LiveJSON.object(tools.execute(name: name, arguments: arguments)) ?? [:]
        if let error = output["error"] as? String {
            return Self.failure(.invalidArguments, error)
        }
        var payload = output
        payload["ok"] = true
        return payload
    }

    // MARK: - Shared values

    private func totals(on day: Date) -> [String: Any] {
        var totals: [String: Any] = [
            "date": iso(day),
            "calories": stores.food.calories(for: day),
            "protein_g": VoiceAssistantFoodBuilder.rounded(stores.food.protein(for: day)),
            "carbs_g": VoiceAssistantFoodBuilder.rounded(stores.food.carbs(for: day)),
            "fat_g": VoiceAssistantFoodBuilder.rounded(stores.food.fat(for: day)),
            "foods_logged": stores.food.entries(for: day).count,
        ]
        if appDefaults.bool(forKey: WaterSettings.enabledKey) {
            totals["water_ml"] = stores.water.total(on: day)
        }
        if workoutDiaryEnabled, let burned = stores.workouts.caloriesBurned(on: day) {
            totals["workout_calories_burned"] = burned
        }
        return totals
    }

    private func goals() -> [String: Any] {
        let profile = stores.profile()
        var goals: [String: Any] = [
            "calories": profile.effectiveCalories,
            "protein_g": profile.effectiveProtein,
            "carbs_g": profile.effectiveCarbs,
            "fat_g": profile.effectiveFat,
        ]
        if appDefaults.bool(forKey: WaterSettings.enabledKey) {
            goals["water_ml"] = appDefaults.object(forKey: WaterSettings.dailyGoalKey) as? Int ?? WaterSettings.defaultDailyGoalMl
        }
        return goals
    }

    private func fastingState() -> [String: Any] {
        var state: [String: Any] = ["tracking": appDefaults.bool(forKey: FastingSettings.enabledKey)]
        if let active = stores.fasting.activeSession {
            state["active"] = true
            state["started_at"] = isoTimestamp(active.startedAt)
            state["goal_hours"] = Double(active.goalMinutes) / 60
            state["elapsed_minutes"] = Int(active.duration(at: now()) / 60)
        } else {
            state["active"] = false
        }
        return state
    }

    private func entryPayload(_ entry: FoodEntry) -> [String: Any] {
        [
            "entry_id": entry.id.uuidString,
            "name": entry.name,
            "calories": entry.calories,
            "protein_g": VoiceAssistantFoodBuilder.rounded(entry.protein),
            "carbs_g": VoiceAssistantFoodBuilder.rounded(entry.carbs),
            "fat_g": VoiceAssistantFoodBuilder.rounded(entry.fat),
            "serving": VoiceAssistantFoodBuilder.servingText(for: entry),
            "meal": entry.mealType.rawValue,
            "date": iso(entry.timestamp),
            "time": VoiceAssistantParsing.clockTime(entry.timestamp, calendar: calendar),
        ]
    }

    private var weightUnitIsMetric: Bool {
        appDefaults.string(forKey: WeightUnit.storageKey).flatMap(WeightUnit.init(rawValue:)) == .kg
    }

    private var currentWeightUnit: WeightUnit {
        weightUnitIsMetric ? .kg : .lbs
    }

    private var waterUnit: WaterUnit {
        appDefaults.string(forKey: WaterSettings.unitKey).flatMap(WaterUnit.init(rawValue:)) ?? .defaultUnit
    }

    private var unitSystem: String {
        let height = appDefaults.string(forKey: HeightUnit.storageKey).flatMap(HeightUnit.init(rawValue:)) ?? .ftin
        switch (height, weightUnitIsMetric) {
        case (.cm, true): return "metric"
        case (.ftin, false): return "imperial"
        default: return "mixed"
        }
    }

    private var adaptiveGoalsEnabled: Bool {
        guard appDefaults.object(forKey: AdaptiveGoalSettings.enabledKey) != nil else { return true }
        return appDefaults.bool(forKey: AdaptiveGoalSettings.enabledKey)
    }

    private var bodyWeightKg: Double {
        stores.weight.latestEntry?.weightKg ?? stores.profile().weightKg
    }

    private func iso(_ date: Date) -> String {
        VoiceAssistantParsing.isoDay(date, calendar: calendar)
    }

    private func isoTimestamp(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = calendar.timeZone
        return formatter.string(from: date)
    }

    static func weightText(kilograms: Double, metric: Bool) -> String {
        metric
            ? String(format: "%.1f kg", kilograms)
            : String(format: "%.1f lb", kilograms * VoiceAssistantParsing.poundsPerKilogram)
    }

    static func failure(_ code: ErrorCode, _ message: String, extra: [String: Any] = [:]) -> [String: Any] {
        var payload = extra
        payload["ok"] = false
        payload["error"] = code.rawValue
        payload["message"] = message
        return payload
    }

    static func json(_ object: [String: Any]) -> String {
        LiveJSON.string(object)
    }
}
