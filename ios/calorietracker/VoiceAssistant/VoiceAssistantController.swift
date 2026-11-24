import AVFoundation
import Observation
import PhotosUI
import RevenueCat
import SwiftUI
import UIKit
import os

private let logger = Logger(subsystem: "com.apoorvdarshan.calorietracker", category: "VoiceAssistant")

/// Sheets presented from the assistant's window.
enum VoiceAssistantSheet: String, Identifiable {
    case paywall
    case credits

    var id: String { rawValue }
}

/// A problem shown in the assistant after a session could not start or ended badly.
struct VoiceAssistantFailure: Equatable {
    enum Action: Equatable {
        case retry
        case openSystemSettings
        case openAISettings
    }

    let message: String
    let action: Action?
}

/// The live voice assistant ("the orb"): one session at a time, with the app's
/// tools, captions, camera, cards and Coach transcripts. The overlay window and
/// the Home button observe it; ContentView attaches the app's stores.
@MainActor
@Observable
final class VoiceAssistantController {
    static let shared = VoiceAssistantController()

    enum Presentation: Equatable {
        case hidden
        /// No way to talk yet: subscribe, or add a key.
        case access(VoiceAssistantAccessReason)
        /// The hosted voice minutes and credits ran out.
        case outOfMinutes
        case failed(VoiceAssistantFailure)
        case session
    }

    enum Phase: Equatable {
        case idle
        case connecting
        case live
    }

    /// The stores ContentView hands over once the app's data is loaded.
    struct AppData {
        let stores: VoiceAssistantStores
        let chat: ChatStore
        let bodyMeasurements: BodyMeasurementStore
    }

    // MARK: Limits

    /// A session ends after this long with no speech either way (not while a tool waits for the user).
    static let idleTimeout: TimeInterval = 45
    /// …and after this long in total.
    static let maximumDuration: TimeInterval = 15 * 60
    static let connectTimeout: TimeInterval = 20
    /// Gemini and hosted sessions see one camera frame a second.
    static let cameraFrameInterval: TimeInterval = 1
    /// After `end_conversation`, the goodbye may play for at most this long.
    static let goodbyeLimit: TimeInterval = 6

    // MARK: Observed state

    private(set) var presentation: Presentation = .hidden {
        didSet {
            // Keep both expanded and minimized conversations awake, then
            // restore Auto-Lock on every exit, including connection failures.
            UIApplication.shared.isIdleTimerDisabled = presentation == .session
        }
    }
    /// Expanded fills the screen; compact is a floating bubble with a caption pill.
    var isExpanded = true {
        didSet {
            if !isExpanded { closeCamera() }
        }
    }
    private(set) var phase: Phase = .idle
    private(set) var provider: LiveProvider?
    private(set) var sessionMascot = VoiceMascotProfile()
    private(set) var isAssistantSpeaking = false
    private(set) var isAwaitingReply = false
    private(set) var runningTools = 0
    private(set) var userCaption = ""
    private(set) var assistantCaption = ""
    /// A short message about the session, such as a provider error, shown for a few seconds.
    private(set) var banner: String?
    /// Hosted voice time left (plan and credits), in seconds, as the Worker reports it.
    private(set) var hostedSecondsRemaining: Int?
    private(set) var cameraMode: VoiceAssistantCamera.Mode?
    private(set) var isCapturingPhoto = false
    let cards = VoiceAssistantCardStore()

    // Presented from the assistant's window.
    var photoPickerPresented = false
    var photoPickerItems: [PhotosPickerItem] = []
    private(set) var photoPickerLimit = VoiceAssistantToolExecutor.maximumPickedPhotos
    /// The one sheet the assistant's window shows (a single `.sheet(item:)`).
    var sheet: VoiceAssistantSheet?

    var isActive: Bool { presentation == .session }
    var isBusy: Bool { isActive || isStarting }

    var orbMode: LiveOrbMode {
        guard presentation == .session else { return .idle }
        if phase == .connecting || runningTools > 0 || isCapturingPhoto { return .thinking }
        if isAssistantSpeaking { return .speaking }
        if isAwaitingReply { return .thinking }
        return phase == .live ? .listening : .idle
    }

    // MARK: Dependencies

    @ObservationIgnored let navigator: VoiceAssistantNavigator
    @ObservationIgnored let settings: VoiceAssistantSettings
    @ObservationIgnored let camera: any VoiceAssistantCameraIO
    @ObservationIgnored private var appData: AppData?
    @ObservationIgnored private var didRefreshEntitlement = false
    private var isStarting = false
    @ObservationIgnored private let makeSocket: LiveSession.SocketFactory
    @ObservationIgnored private let makeAudio: @MainActor () -> any LiveAudioIO

    // MARK: Session state

    @ObservationIgnored private var session: LiveSession?
    @ObservationIgnored private var audio: (any LiveAudioIO)?
    @ObservationIgnored private var executor: VoiceAssistantToolExecutor?
    @ObservationIgnored private var sessionNumber = 0
    @ObservationIgnored private var startedAt = Date.distantPast
    @ObservationIgnored private var lastActivity = Date.distantPast
    @ObservationIgnored private var lastAudioAt = Date.distantPast
    @ObservationIgnored private var watchdog: Task<Void, Never>?
    @ObservationIgnored private var frameLoop: Task<Void, Never>?
    @ObservationIgnored private var bannerTask: Task<Void, Never>?
    @ObservationIgnored private var endingTask: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    // Turn and transcript bookkeeping.
    @ObservationIgnored private var pendingUserText = ""
    @ObservationIgnored private var replyText = ""
    @ObservationIgnored private var replyItem: (id: String, start: TimeInterval)?
    @ObservationIgnored private var userTurnStartedAt: Date?
    @ObservationIgnored private var userCaptionIsStale = false
    @ObservationIgnored private var assistantCaptionIsStale = false
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    // Tool calls. OpenAI and xAI need `response.create` after results, but refuse
    // it while a response is still being generated.
    @ObservationIgnored private var toolQueue: Task<Void, Never>?
    @ObservationIgnored private var toolTasks: [String: Task<LiveToolResponse, Never>] = [:]
    @ObservationIgnored private var cancelledCallIDs: Set<String> = []
    @ObservationIgnored private var pendingCallIDs: Set<String> = []
    @ObservationIgnored private var responseActive = false
    @ObservationIgnored private var responseNeeded = false
    @ObservationIgnored private var isEndingConversation = false

    // Waiting for the user.
    @ObservationIgnored private var waitingForUser = 0
    @ObservationIgnored private var pickerContinuation: CheckedContinuation<VoiceAssistantWait<[UIImage]>, Never>?
    @ObservationIgnored private var pickerNumber = 0
    @ObservationIgnored private var isLoadingPickedPhotos = false
    @ObservationIgnored private let barcodeRequest = VoiceAssistantBarcodeRequest()
    @ObservationIgnored private var cameraNumber = 0

    init(
        navigator: VoiceAssistantNavigator? = nil,
        settings: VoiceAssistantSettings = VoiceAssistantSettings(),
        camera: (any VoiceAssistantCameraIO)? = nil,
        makeSocket: @escaping LiveSession.SocketFactory = { URLSessionLiveSocket(request: $0) },
        makeAudio: @escaping @MainActor () -> any LiveAudioIO = { LiveAudioEngine() }
    ) {
        self.navigator = navigator ?? .shared
        self.settings = settings
        self.camera = camera ?? VoiceAssistantCamera()
        self.makeSocket = makeSocket
        self.makeAudio = makeAudio
        self.camera.onBarcode = { [weak self] code in
            MainActor.assumeIsolated {
                self?.finishBarcode(.value(code))
            }
        }
    }

    func attach(_ data: AppData) {
        appData = data
        data.chat.onReset = { [weak self] in self?.end(.coachHistoryReset) }
    }

    /// The loudness the orb shows: the reply while it plays, the microphone otherwise.
    func currentLevel() -> Double {
        guard let audio, presentation == .session else { return 0 }
        return isAssistantSpeaking ? audio.playbackLevel : audio.microphoneLevel
    }

    // MARK: - Starting and ending

    /// Opens the assistant: starts a session, or explains how to get access.
    func start() {
        switch presentation {
        case .session:
            isExpanded = true
            return
        case .hidden, .access, .outOfMinutes, .failed:
            break
        }
        guard let appData, !isStarting else { return }
        VoicePreviewController.shared.stop()
        isStarting = true
        let number = sessionNumber
        Task {
            defer { isStarting = false }
            var route = VoiceAssistantRouting.current(settings: settings)
            // Right after launch the subscription may not be loaded yet; check once
            // before telling a subscriber to subscribe.
            if case .needsAccess(let reason) = route, reason == .noProvider || reason == .needsSubscription,
               !RevenueCatManager.shared.hasLoadedCustomerInfo, !didRefreshEntitlement, Purchases.isConfigured {
                didRefreshEntitlement = await RevenueCatManager.shared.refreshCustomerInfo()
                route = VoiceAssistantRouting.current(settings: settings)
            }
            guard number == sessionNumber, UIApplication.shared.applicationState != .background else { return }
            switch route {
            case .needsAccess(let reason):
                isExpanded = true
                presentation = .access(reason)
            case .ready(let provider):
                await begin(provider: provider, data: appData)
            }
        }
    }

    /// Closes whatever the assistant shows, ending a session.
    func dismiss() {
        if presentation == .session {
            end(.user)
        } else {
            // Retire an entitlement/permission check that has not started a
            // connection yet, so dismissing cannot reopen the assistant.
            sessionNumber += 1
            presentation = .hidden
        }
    }

    private func begin(provider: LiveProvider, data: AppData) async {
        sessionNumber += 1
        let number = sessionNumber
        sessionMascot = VoiceMascotPreferences.load().profile
        presentation = .session
        isExpanded = true
        phase = .connecting
        self.provider = provider
        resetTurnState()
        hostedSecondsRemaining = nil
        clearBanner()
        observeAppLifecycle(session: number)

        guard await microphoneAllowed() else {
            fail(VoiceAssistantFailure(
                message: String(localized: "Microphone access is off. Turn it on for Fud AI in the iOS Settings app to talk to the assistant."),
                action: .openSystemSettings
            ), session: number)
            return
        }
        guard number == sessionNumber, presentation == .session else { return }

        let credential: LiveSessionConfig.Credential
        if provider == .hosted {
            guard Purchases.isConfigured else {
                fail(VoiceAssistantFailure(message: String(localized: "Fud AI couldn't check your subscription. Try again in a moment."), action: .retry), session: number)
                return
            }
            // The relay still rechecks entitlement. Reuse the same identity from
            // the local SDK cache instead of waiting for a second billing request.
            let purchases = Purchases.shared
            credential = .hostedUser(purchases.cachedCustomerInfo?.originalAppUserId ?? purchases.appUserID)
        } else {
            guard let key = provider.savedKey else {
                presentation = .access(.missingKey(provider))
                phase = .idle
                return
            }
            credential = .apiKey(key)
        }
        guard number == sessionNumber, presentation == .session else { return }

        let workoutDiary = settings.workoutDiaryEnabled
        let instructions = VoiceAssistantInstructions.build(
            session: .init(
                cameraFrames: provider.cameraFrames,
                autoCapture: settings.autoCapture,
                confirmSaves: settings.confirmSaves,
                workoutDiaryEnabled: workoutDiary,
                language: settings.language,
                appLanguageCode: VoiceAssistantLanguage.appLanguageCode,
                weightMetric: WeightUnit.current == .kg,
                heightMetric: HeightUnit.current == .cm,
                waterInOunces: UserDefaults.standard.string(forKey: WaterSettings.unitKey) == WaterUnit.fluidOunces.rawValue,
                coachHistoryEnabled: settings.saveToCoach
            ),
            stores: data.stores,
            bodyMeasurements: data.bodyMeasurements.entries,
            waterTracking: UserDefaults.standard.bool(forKey: WaterSettings.enabledKey),
            waterGoalMl: UserDefaults.standard.object(forKey: WaterSettings.dailyGoalKey) as? Int ?? WaterSettings.defaultDailyGoalMl,
            fastingTracking: UserDefaults.standard.bool(forKey: FastingSettings.enabledKey),
            mascot: sessionMascot
        )
        let config = LiveSessionConfig(
            provider: provider,
            model: settings.model(for: provider),
            voice: settings.voice(for: provider),
            credential: credential,
            instructions: instructions,
            tools: VoiceAssistantToolCatalog.declarations(workoutDiaryEnabled: workoutDiary)
        )

        cards.clear()
        executor = VoiceAssistantToolExecutor(
            stores: data.stores,
            settings: settings,
            cards: cards,
            workoutDiaryEnabled: workoutDiary,
            host: self,
            coachHistory: { data.chat.contextMessages() }
        )

        let wire = provider.wire
        let session = LiveSession(config: config, wire: wire, makeSocket: makeSocket) { [weak self] event in
            // The main queue keeps events in order (reply audio must play in sequence).
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    self?.handle(event, session: number)
                }
            }
        }
        let audio = makeAudio()
        audio.onPlaybackFinished = { [weak self] in self?.playbackFinished(session: number) }
        audio.onInterrupted = { [weak self] in
            guard self?.sessionNumber == number else { return }
            self?.end(.interrupted)
        }
        do {
            try audio.start(inputSampleRate: wire.inputSampleRate, outputSampleRate: wire.outputSampleRate) { @Sendable [weak session] pcm in
                session?.send(audio: pcm)
            }
        } catch {
            logger.error("[Voice] Audio failed to start: \(error.localizedDescription, privacy: .public)")
            audio.stop()
            fail(VoiceAssistantFailure(message: String(localized: "The microphone isn't available right now. Try again."), action: .retry), session: number)
            return
        }

        self.session = session
        self.audio = audio
        startedAt = .now
        lastActivity = startedAt
        session.connect()
        startWatchdog(session: number)
        logger.info("[Voice] Connecting to \(provider.rawValue, privacy: .public)")
    }

    private enum EndReason {
        case user
        case idle
        case timeLimit
        case conversationEnded
        case background
        case interrupted
        case coachHistoryReset
        case outOfMinutes
        case needsSubscription
        case failure(VoiceAssistantFailure)
    }

    private func end(_ reason: EndReason) {
        guard session != nil || audio != nil || presentation == .session else { return }
        sessionNumber += 1
        watchdog?.cancel()
        watchdog = nil
        frameLoop?.cancel()
        frameLoop = nil
        endingTask?.cancel()
        endingTask = nil
        toolQueue?.cancel()
        toolQueue = nil
        for task in toolTasks.values { task.cancel() }
        toolTasks.removeAll()
        finishPicker(.cancelled)
        finishBarcode(.cancelled)
        photoPickerPresented = false
        session?.close()
        session = nil
        audio?.stop()
        audio = nil
        camera.stop()
        cameraMode = nil
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
        saveTask?.cancel()
        saveTask = nil
        if case .coachHistoryReset = reason { resetTurnState() } else { saveTurn() }
        executor?.reset()
        executor = nil
        cards.clear()

        phase = .idle
        isAssistantSpeaking = false
        isAwaitingReply = false
        runningTools = 0
        isCapturingPhoto = false
        userCaption = ""
        assistantCaption = ""
        pendingCallIDs.removeAll()
        cancelledCallIDs.removeAll()
        responseActive = false
        responseNeeded = false
        isEndingConversation = false
        waitingForUser = 0
        sheet = nil
        isLoadingPickedPhotos = false
        photoPickerItems = []

        switch reason {
        case .user, .idle, .conversationEnded, .background, .interrupted, .coachHistoryReset:
            presentation = .hidden
        case .timeLimit:
            presentation = .failed(VoiceAssistantFailure(
                message: String(localized: "This conversation reached the 15-minute limit. Start a new one to keep going."),
                action: .retry
            ))
        case .outOfMinutes:
            presentation = .outOfMinutes
        case .needsSubscription:
            presentation = .access(.needsSubscription)
        case .failure(let failure):
            presentation = .failed(failure)
        }
        logger.info("[Voice] Session ended")
    }

    private func fail(_ failure: VoiceAssistantFailure, session number: Int) {
        guard number == sessionNumber else { return }
        end(.failure(failure))
    }

    private func microphoneAllowed() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        case .undetermined: return await AVAudioApplication.requestRecordPermission()
        @unknown default: return false
        }
    }

    private func observeAppLifecycle(session number: Int) {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = [
            NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard self?.sessionNumber == number else { return }
                    self?.end(.background)
                }
            },
        ]
    }

    // MARK: - Provider events

    private func handle(_ event: LiveEvent, session number: Int) {
        guard number == sessionNumber, presentation == .session else { return }
        switch event {
        case .ready:
            phase = .live
            noteActivity()
        case .audio(let pcm, let itemID):
            noteActivity()
            lastAudioAt = .now
            guard let audio else { return }
            if let itemID, replyItem?.id != itemID {
                replyItem = (itemID, audio.scheduledEnd)
            }
            audio.play(pcm)
            isAssistantSpeaking = true
            isAwaitingReply = false
        case .assistantText(let text):
            noteActivity()
            if assistantCaptionIsStale {
                assistantCaption = ""
                assistantCaptionIsStale = false
            }
            replyText += text
            assistantCaption += text
        case .userText(let text, let replacing):
            noteActivity()
            if userCaptionIsStale {
                userCaption = ""
                userCaptionIsStale = false
            }
            if userTurnStartedAt == nil { userTurnStartedAt = .now }
            pendingUserText = replacing ? text : pendingUserText + text
            userCaption = pendingUserText
        case .userSpeechStarted:
            noteActivity()
            // The last turn is saved before this one's words replace it.
            if saveTask != nil {
                saveTask?.cancel()
                saveTask = nil
                saveTurn()
            }
            interruptReply(tellProvider: true)
            userCaptionIsStale = true
            userTurnStartedAt = .now
            sendCameraFrameForTurn()
        case .interrupted:
            interruptReply(tellProvider: false)
        case .turnComplete:
            isAwaitingReply = false
            finishTurn()
        case .responseStarted:
            responseActive = true
            if !isAssistantSpeaking { isAwaitingReply = true }
        case .responseDone(let hasToolCalls, _):
            // Barge-in cancellation is expected during a conversation; the
            // preview's stricter completion check must not turn it into a failure.
            responseActive = false
            isAwaitingReply = false
            if !hasToolCalls { finishTurn() }
            requestResponseIfReady()
        case .toolCalls(let calls):
            noteActivity()
            runTools(calls, session: number)
        case .toolCallCancellation(let ids):
            cancelTools(ids)
        case .hostedSecondsRemaining(let seconds):
            hostedSecondsRemaining = seconds
        case .hostedError(let code, let detail):
            logger.info("[Voice] Hosted relay closing: \(code, privacy: .public) \(detail ?? "", privacy: .public)")
            // The relay closes right after; its close code then belongs to an ended session.
            end(Self.endReason(forHostedError: code))
        case .error(let message, let code):
            // Benign races with the response lifecycle; nothing for the user to do.
            if code == "conversation_already_has_active_response" {
                // Our response.create lost to one the provider started; ask again once it finishes.
                responseActive = true
                responseNeeded = true
                return
            }
            if code == "response_cancel_not_active" { return }
            logger.error("[Voice] The provider reported an error")
            if phase == .connecting {
                end(.failure(VoiceAssistantFailure(
                    message: String(localized: "The voice service had a problem. Try again."),
                    action: .retry
                )))
                return
            }
            showBanner(message)
        case .closed(let reason):
            handleClose(reason)
        case .resumptionHandle, .goAway:
            break
        }
    }

    /// What a `fudVoice` error sent before the relay closes means for the user.
    private static func endReason(forHostedError code: String) -> EndReason {
        switch code {
        case "voice_quota_exceeded":
            return .outOfMinutes
        case "session_limit":
            return .timeLimit
        case "rate_limited":
            return .failure(VoiceAssistantFailure(message: String(localized: "Too many voice sessions right now. Try again in a minute."), action: .retry))
        case "voice_unavailable", "upstream_error":
            return .failure(VoiceAssistantFailure(message: String(localized: "Voice is unavailable right now. Try again later."), action: .retry))
        default:
            // invalid_setup, invalid_message, setup_timeout and codes added later.
            return .failure(VoiceAssistantFailure(message: String(localized: "The voice service had a problem. Try again."), action: .retry))
        }
    }

    private func handleClose(_ reason: LiveEndReason?) {
        guard let reason else {
            end(.failure(VoiceAssistantFailure(message: String(localized: "The voice connection ended."), action: .retry)))
            return
        }
        let hosted = provider == .hosted
        switch reason {
        case .refused(let status):
            switch (hosted, status) {
            case (true, 402):
                end(.outOfMinutes)
            case (true, 403):
                end(.needsSubscription)
            case (true, 401):
                end(.failure(VoiceAssistantFailure(message: String(localized: "Fud AI couldn't verify your subscription. Try Restore Purchases in Settings → AI Access."), action: .retry)))
            case (true, 429):
                end(.failure(VoiceAssistantFailure(message: String(localized: "Too many voice sessions right now. Try again in a minute."), action: .retry)))
            case (true, 503):
                end(.failure(VoiceAssistantFailure(message: String(localized: "Voice is unavailable right now. Try again later."), action: .retry)))
            case (false, 401), (false, 403):
                end(.failure(VoiceAssistantFailure(
                    message: String(localized: "\(provider?.displayName ?? "") rejected your API key or doesn't allow this voice model. Check it in Settings → AI Providers & Fallbacks."),
                    action: .openAISettings
                )))
            default:
                end(.failure(VoiceAssistantFailure(message: String(localized: "The voice service refused the connection (\(status)). Try again."), action: .retry)))
            }
        case .closedByServer(let code, let message):
            switch code {
            case LiveEndReason.outOfMinutesCloseCode:
                end(.outOfMinutes)
            case LiveEndReason.sessionLimitCloseCode:
                end(.timeLimit)
            case LiveEndReason.upstreamFailureCloseCode:
                end(.failure(VoiceAssistantFailure(message: String(localized: "The voice service had a problem. Try again."), action: .retry)))
            case LiveEndReason.normalCloseCode:
                end(.failure(VoiceAssistantFailure(message: String(localized: "The voice connection ended."), action: .retry)))
            default:
                // Gemini explains a refused own key or model in the close reason.
                let detail = hosted ? nil : message.flatMap { $0.isEmpty ? nil : $0 }
                end(.failure(VoiceAssistantFailure(
                    message: detail ?? String(localized: "The voice service had a problem. Try again."),
                    action: hosted ? .retry : .openAISettings
                )))
            }
        case .failed:
            end(.failure(VoiceAssistantFailure(message: String(localized: "The voice connection ended. Check your internet connection and try again."), action: .retry)))
        }
    }

    private func playbackFinished(session number: Int) {
        guard number == sessionNumber else { return }
        isAssistantSpeaking = false
        noteActivity()
    }

    /// Stops the reply because the user spoke over it. OpenAI is told how much
    /// was heard, so the conversation history matches what the user heard.
    private func interruptReply(tellProvider: Bool) {
        guard let audio, audio.isPlaying || isAssistantSpeaking else { return }
        if tellProvider, let replyItem {
            let heardMs = Int(((audio.playbackPosition - replyItem.start) * 1000).rounded())
            session?.truncate(itemID: replyItem.id, audioEndMs: heardMs)
        }
        audio.stopPlayback()
        isAssistantSpeaking = false
    }

    // MARK: - Turns and Coach

    private func finishTurn() {
        assistantCaptionIsStale = true
        userCaptionIsStale = true
        // OpenAI and xAI transcribe the user's words after the reply starts, so
        // their transcript gets a moment to arrive before the turn is saved.
        let delay: TimeInterval = session?.wire.needsResponseCreate == true ? 1.5 : 0
        saveTask?.cancel()
        guard delay > 0 else {
            saveTurn()
            return
        }
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            self?.saveTurn()
        }
    }

    /// Adds what the user said, then the reply, to the Coach chat.
    private func saveTurn() {
        let user = pendingUserText.trimmingCharacters(in: .whitespacesAndNewlines)
        let reply = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        let userAt = userTurnStartedAt ?? .now
        pendingUserText = ""
        replyText = ""
        replyItem = nil
        userTurnStartedAt = nil
        guard settings.saveToCoach, let chat = appData?.chat else { return }
        if !user.isEmpty {
            chat.append(ChatMessage(role: .user, content: user, timestamp: userAt))
        }
        if !reply.isEmpty {
            chat.append(ChatMessage(role: .assistant, content: reply))
        }
    }

    private func resetTurnState() {
        pendingUserText = ""
        replyText = ""
        replyItem = nil
        userTurnStartedAt = nil
        userCaption = ""
        assistantCaption = ""
        userCaptionIsStale = false
        assistantCaptionIsStale = false
        isAssistantSpeaking = false
        isAwaitingReply = false
        runningTools = 0
        responseActive = false
        responseNeeded = false
        pendingCallIDs.removeAll()
        cancelledCallIDs.removeAll()
        isEndingConversation = false
    }

    // MARK: - Tools

    private func runTools(_ calls: [LiveToolCall], session number: Int) {
        guard let session else { return }
        let separateResponses = session.wire.needsResponseCreate
        if separateResponses {
            pendingCallIDs.formUnion(calls.map(\.id))
        }
        let previous = toolQueue
        let task = Task { [weak self] in
            await previous?.value
            guard let self, number == self.sessionNumber, !Task.isCancelled else { return }
            var responses: [LiveToolResponse] = []
            for call in calls {
                guard number == self.sessionNumber, !Task.isCancelled else { return }
                guard !self.cancelledCallIDs.contains(call.id), let executor = self.executor else { continue }
                self.runningTools += 1
                self.noteActivity()
                let execution = Task { @MainActor in
                    let result = await executor.execute(call)
                    return LiveToolResponse(id: call.id, name: call.name, outputJSON: LiveJSON.string(result))
                }
                self.toolTasks[call.id] = execution
                let response = await execution.value
                guard number == self.sessionNumber else { return }
                self.toolTasks.removeValue(forKey: call.id)
                self.runningTools = max(0, self.runningTools - 1)
                self.noteActivity()
                guard number == self.sessionNumber, !self.cancelledCallIDs.contains(call.id) else { continue }
                if separateResponses {
                    self.session?.send(toolResponses: [response])
                    await self.followUp(call)
                    self.pendingCallIDs.remove(call.id)
                    self.responseNeeded = true
                    self.requestResponseIfReady()
                } else {
                    responses.append(response)
                }
            }
            guard number == self.sessionNumber, !responses.isEmpty else { return }
            self.session?.send(toolResponses: responses)
            for call in calls where !self.cancelledCallIDs.contains(call.id) { await self.followUp(call) }
        }
        toolQueue = task
    }

    /// After some tools the model should see the camera straight away.
    private func followUp(_ call: LiveToolCall) async {
        guard call.name == "open_camera", isExpanded, cameraMode != nil, provider?.cameraFrames == .perTurn else { return }
        let number = sessionNumber
        let cameraNumber = cameraNumber
        for _ in 0..<10 where camera.latestJPEG() == nil {
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled, number == sessionNumber, cameraNumber == self.cameraNumber,
                  isExpanded, cameraMode != nil else { return }
        }
        if let frame = camera.latestJPEG() {
            session?.send(image: frame)
        }
    }

    /// Gemini: the user interrupted, so these calls are abandoned and get no response.
    private func cancelTools(_ ids: [String]) {
        cancelledCallIDs.formUnion(ids)
        for id in ids {
            pendingCallIDs.remove(id)
            toolTasks[id]?.cancel()
        }
        // Only the cancelled call's cancellation handler closes its picker or
        // scanner. An unrelated queued cancellation must not dismiss either.
    }

    private func requestResponseIfReady() {
        guard let session, session.wire.needsResponseCreate, responseNeeded,
              pendingCallIDs.isEmpty, !responseActive, !isEndingConversation else { return }
        responseNeeded = false
        responseActive = true
        isAwaitingReply = true
        session.requestResponse()
    }

    /// Tells the model about something the user did on screen.
    private func tellModel(_ line: String) {
        guard let session, presentation == .session else { return }
        noteActivity()
        session.send(contextText: line)
        if session.wire.needsResponseCreate {
            responseNeeded = true
            requestResponseIfReady()
        }
    }

    // MARK: - Card taps

    func confirmCard(_ id: UUID) {
        let number = sessionNumber
        Task {
            guard let line = await executor?.confirm(cardID: id) else { return }
            guard number == sessionNumber else { return }
            tellModel(line)
        }
    }

    func cancelCard(_ id: UUID) {
        guard let line = executor?.cancel(cardID: id) else { return }
        tellModel(line)
    }

    func undoCard(_ id: UUID) {
        guard let line = executor?.undo(cardID: id) else { return }
        tellModel(line)
    }

    func logDraft(_ draftID: String) {
        let number = sessionNumber
        Task {
            guard let line = await executor?.logDraft(draftID) else { return }
            guard number == sessionNumber else { return }
            tellModel(line)
        }
    }

    func dismissCard(_ id: UUID) {
        cards.remove(id: id)
    }

    func noticeAction(_ action: VoiceAssistantCard.Notice.Action) {
        switch action {
        case .none:
            break
        case .buyCredits:
            sheet = RevenueCatManager.shared.hasHostedEntitlement ? .credits : .paywall
        case .openAISettings:
            openAISettings()
        }
    }

    /// The shutter in the camera panel: take the photo and analyze it now.
    func shutterTapped() {
        guard !isCapturingPhoto, cameraMode == .food, let executor else { return }
        let number = sessionNumber
        isCapturingPhoto = true
        Task {
            let result = await executor.execute(name: "capture_photo", arguments: [:])
            guard number == sessionNumber else { return }
            isCapturingPhoto = false
            tellModel("[App] The user tapped the camera shutter. capture_photo result: \(LiveJSON.string(result))")
        }
    }

    /// The close button on the camera panel.
    func closeCameraTapped() {
        let wasScanning = barcodeRequest.isWaiting
        closeCamera()
        if !wasScanning {
            tellModel("[App] The user closed the camera.")
        }
    }

    func minimize() {
        if cameraMode != nil { closeCameraTapped() }
        isExpanded = false
    }

    // MARK: - Access screens

    func openAISettings() {
        if isActive { end(.user) }
        presentation = .hidden
        navigator.show(.settings, settingsSection: .aiProviders)
    }

    func openSystemSettings() {
        presentation = .hidden
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    func retry() {
        presentation = .hidden
        start()
    }

    // MARK: - Photo picker

    func photosPicked(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty, pickerContinuation != nil else { return }
        isLoadingPickedPhotos = true
        let number = pickerNumber
        let limit = photoPickerLimit
        Task {
            var images: [UIImage] = []
            for item in items.prefix(limit) {
                if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data) {
                    images.append(image)
                }
            }
            guard number == pickerNumber, pickerContinuation != nil else { return }
            isLoadingPickedPhotos = false
            photoPickerItems = []
            finishPicker(images.isEmpty ? .cancelled : .value(images))
        }
    }

    func photoPickerDismissed() {
        let number = pickerNumber
        // A selection updates the binding around the time the sheet closes.
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            guard number == pickerNumber, !isLoadingPickedPhotos, photoPickerItems.isEmpty else { return }
            finishPicker(.cancelled)
        }
    }

    private func finishPicker(_ result: VoiceAssistantWait<[UIImage]>) {
        guard let continuation = pickerContinuation else { return }
        photoPickerPresented = false
        photoPickerItems = []
        pickerContinuation = nil
        pickerNumber += 1
        isLoadingPickedPhotos = false
        continuation.resume(returning: result)
    }

    private func finishBarcode(_ result: VoiceAssistantWait<String>) {
        barcodeRequest.finish(result)
    }

    // MARK: - Camera frames

    private func startFrameLoop() {
        frameLoop?.cancel()
        guard provider?.cameraFrames == .continuous else { return }
        let number = sessionNumber
        frameLoop = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, number == self.sessionNumber, self.isExpanded, self.cameraMode != nil else { return }
                if self.phase == .live, let frame = self.camera.latestJPEG() {
                    self.session?.send(image: frame)
                }
                try? await Task.sleep(for: .seconds(Self.cameraFrameInterval))
            }
        }
    }

    /// OpenAI sees the latest frame with each thing the user says.
    private func sendCameraFrameForTurn() {
        guard provider?.cameraFrames == .perTurn, isExpanded, cameraMode != nil, let frame = camera.latestJPEG() else { return }
        session?.send(image: frame)
    }

    // MARK: - Timers and banners

    private func noteActivity() {
        lastActivity = .now
    }

    private func startWatchdog(session number: Int) {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, number == self.sessionNumber else { return }
                self.checkTimeouts()
            }
        }
    }

    private func checkTimeouts() {
        guard presentation == .session else { return }
        let now = Date.now
        if phase == .connecting, now.timeIntervalSince(startedAt) > Self.connectTimeout {
            end(.failure(VoiceAssistantFailure(
                message: String(localized: "Couldn't connect to the voice service. Check your connection and try again."),
                action: .retry
            )))
            return
        }
        if now.timeIntervalSince(startedAt) > Self.maximumDuration {
            end(.timeLimit)
            return
        }
        // Someone talking keeps the session alive even before a transcript arrives.
        if (audio?.microphoneLevel ?? 0) > 0.5 || isAssistantSpeaking || runningTools > 0 || waitingForUser > 0
            || cards.cards.contains(where: \.isConfirmation) {
            noteActivity()
        }
        if now.timeIntervalSince(lastActivity) > Self.idleTimeout {
            logger.info("[Voice] Ending an idle session")
            end(.idle)
        }
    }

    private func showBanner(_ message: String) {
        banner = message
        bannerTask?.cancel()
        bannerTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled else { return }
            self?.banner = nil
        }
    }

    private func clearBanner() {
        bannerTask?.cancel()
        bannerTask = nil
        banner = nil
    }
}

// MARK: - Tool host

extension VoiceAssistantController: VoiceAssistantToolHost {
    var cameraFrames: LiveCameraFrames { provider?.cameraFrames ?? .none }
    var sessionProvider: LiveProvider? { provider }
    var currentScreen: VoiceAssistantScreen { navigator.currentScreen }
    var currentSettingsSection: ProfileSettingsCategory? { navigator.settingsSection }
    var homeDiaryDate: Date { navigator.homeDiaryDate }
    var workoutDiaryDate: Date { navigator.currentScreen == .workouts ? navigator.workoutDiaryDate : .now }

    func open(_ screen: VoiceAssistantScreen, settingsSection: ProfileSettingsCategory?) {
        closeCamera()
        navigator.show(screen, settingsSection: settingsSection)
        isExpanded = false
    }

    func showDiary(on day: Date) {
        closeCamera()
        navigator.showDiary(on: day)
        isExpanded = false
    }

    func showWorkoutDiary(on day: Date) {
        closeCamera()
        navigator.showWorkoutDiary(on: day)
        isExpanded = false
    }

    func openFoodEntry(_ entry: FoodEntry) {
        closeCamera()
        navigator.openFoodEntry(entry)
        isExpanded = false
    }

    func openCamera(_ mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult {
        let number = sessionNumber
        cameraNumber += 1
        let cameraNumber = cameraNumber
        isExpanded = true
        if cameraMode == mode { return .started }
        if cameraMode != nil {
            camera.setMode(mode)
            cameraMode = mode
            return .started
        }
        let result = await camera.start(mode: mode)
        guard number == sessionNumber, cameraNumber == self.cameraNumber,
              presentation == .session, isExpanded, !Task.isCancelled else {
            if number == sessionNumber, cameraNumber == self.cameraNumber { camera.stop() }
            return .unavailable
        }
        if result == .started {
            cameraMode = mode
            startFrameLoop()
        }
        return result
    }

    func closeCamera() {
        finishBarcode(.cancelled)
        cameraNumber += 1
        // Also invalidate a permission/start request whose mode is not set yet.
        camera.stop()
        cameraMode = nil
        frameLoop?.cancel()
        frameLoop = nil
    }

    func captureStill() async -> UIImage? {
        let number = sessionNumber
        let request = cameraNumber
        let photo = await camera.captureStill(timeout: 3)
        // A late capture must neither return an old image nor close a replacement camera.
        guard number == sessionNumber, request == cameraNumber else { return nil }
        closeCamera()
        return Task.isCancelled ? nil : photo
    }

    func pickPhotos(limit: Int, timeout: TimeInterval) async -> VoiceAssistantWait<[UIImage]> {
        closeCamera()
        finishPicker(.cancelled)
        pickerNumber += 1
        let number = pickerNumber
        photoPickerLimit = limit
        photoPickerItems = []
        waitingForUser += 1
        defer { waitingForUser = max(0, waitingForUser - 1) }
        photoPickerPresented = true
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard !Task.isCancelled else { return }
            self?.photoPickerPresented = false
            self?.finishPicker(.timeout)
        }
        defer { timeoutTask.cancel() }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                pickerContinuation = continuation
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                guard self?.pickerNumber == number else { return }
                self?.photoPickerPresented = false
                self?.finishPicker(.cancelled)
            }
        }
    }

    func scanBarcode(timeout: TimeInterval) async -> VoiceAssistantWait<String> {
        let request = barcodeRequest.begin()
        switch await openCamera(.barcode) {
        case .denied: return .denied
        case .unavailable: return .unavailable
        case .started: break
        }
        guard barcodeRequest.isCurrent(request), !Task.isCancelled else {
            if barcodeRequest.isCurrent(request), cameraMode == .barcode { closeCamera() }
            return .cancelled
        }
        waitingForUser += 1
        defer { waitingForUser = max(0, waitingForUser - 1) }
        let timeoutTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(timeout))
            guard !Task.isCancelled else { return }
            self?.barcodeRequest.finish(.timeout, request: request)
        }
        defer { timeoutTask.cancel() }
        let result: VoiceAssistantWait<String> = await withTaskCancellationHandler {
            await barcodeRequest.wait(for: request)
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.barcodeRequest.finish(.cancelled, request: request)
            }
        }
        if barcodeRequest.isCurrent(request), cameraMode == .barcode {
            closeCamera()
        }
        return result
    }

    func endConversation() {
        guard presentation == .session, endingTask == nil else { return }
        isEndingConversation = true
        let number = sessionNumber
        let began = Date.now
        endingTask = Task { [weak self] in
            // Let the goodbye finish playing first.
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(300))
                guard let self, number == self.sessionNumber else { return }
                let quiet = !(self.audio?.isPlaying ?? false) && Date.now.timeIntervalSince(self.lastAudioAt) > 1
                if quiet || Date.now.timeIntervalSince(began) > Self.goodbyeLimit {
                    self.endingTask = nil
                    self.end(.conversationEnded)
                    return
                }
            }
        }
    }
}

#if DEBUG && targetEnvironment(simulator)
// MARK: - Screenshots

extension VoiceAssistantController {
    /// A finished turn for the README and website screenshots (ScreenshotMode), shown
    /// without connecting: the meal is logged and its Undo card is up.
    func showScreenshotConversation() {
        sessionMascot = VoiceMascotPreferences.load().profile
        provider = .gemini
        phase = .live
        presentation = .session
        isExpanded = true
        isAssistantSpeaking = true
        userCaption = "Log two eggs and a latte for breakfast."
        assistantCaption = "Done, logged to breakfast. That's 290 calories with 20 grams of protein."
        cards.clear()
        cards.show(VoiceAssistantCard(content: .draft(VoiceAssistantCard.Draft(
            draftID: "screenshot",
            isWorkout: false,
            title: "Eggs & latte",
            lines: ["2 large eggs · 100 g · 156 kcal", "Café latte · 245 g · 134 kcal"],
            calories: 290,
            macros: "20P · 11C · 18F",
            thumbnail: nil,
            isLogged: true
        ))))
        cards.show(VoiceAssistantCard(content: .undo(VoiceAssistantCard.Undo(
            text: "Logged Eggs & latte · 290 kcal",
            expiresAt: .now.addingTimeInterval(600)
        ))))
    }
}
#endif
