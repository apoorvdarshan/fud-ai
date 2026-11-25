import Foundation
import Observation
import RevenueCat
import UIKit

/// The preview only needs text in and audio out; it has no tool, camera or mic API.
protocol VoicePreviewSession: AnyObject {
    func connect()
    func send(contextText: String)
    func requestResponse()
    func close()
}

extension LiveSession: VoicePreviewSession {}

/// One bounded, isolated voice sample. Never starts the conversation controller or
/// reads app stores, and never keeps transcripts or provider error payloads.
@MainActor
@Observable
final class VoicePreviewController {
    static let shared = VoicePreviewController()
    static let maximumDuration: TimeInterval = 30

    enum Phase: Equatable {
        case idle
        case preparing
        case playing
    }

    private(set) var phase: Phase = .idle
    private(set) var errorMessage: String?
    var isRunning: Bool { phase != .idle }

    typealias SessionFactory = @MainActor (LiveSessionConfig, @escaping @Sendable (LiveEvent) -> Void) -> any VoicePreviewSession

    @ObservationIgnored private let settings: VoiceAssistantSettings
    @ObservationIgnored private let resolveRoute: @MainActor () -> VoiceAssistantRoute
    @ObservationIgnored private let assistantIsBusy: @MainActor () -> Bool
    @ObservationIgnored private let credential: @MainActor (LiveProvider) async -> LiveSessionConfig.Credential?
    @ObservationIgnored private let makeSession: SessionFactory
    @ObservationIgnored private let makeAudio: @MainActor () -> any LivePlaybackIO
    @ObservationIgnored private let waitForDeadline: @Sendable () async throws -> Void
    @ObservationIgnored private let notificationCenter: NotificationCenter
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var session: (any VoicePreviewSession)?
    @ObservationIgnored private var audio: (any LivePlaybackIO)?
    @ObservationIgnored private var preparation: Task<Void, Never>?
    @ObservationIgnored private var deadline: Task<Void, Never>?
    @ObservationIgnored private var backgroundObserver: NSObjectProtocol?
    @ObservationIgnored private var sentSample = false
    @ObservationIgnored private var generationFinished = false
    @ObservationIgnored private var receivedAudioBytes = 0
    @ObservationIgnored private var maximumAudioBytes = 0
    @ObservationIgnored private var outputSampleRate = 24_000.0
    /// False for GPT-Live, whose sample ends after a quiet moment instead of a turn end.
    @ObservationIgnored private var reportsTurnEnds = true
    @ObservationIgnored private var quietFinish: Task<Void, Never>?
    /// How long GPT-Live audio must pause before its sample counts as finished.
    @ObservationIgnored private let quietFinishDelay: Duration

    init(
        settings: VoiceAssistantSettings = VoiceAssistantSettings(),
        resolveRoute: (@MainActor () -> VoiceAssistantRoute)? = nil,
        assistantIsBusy: @escaping @MainActor () -> Bool = { VoiceAssistantController.shared.isBusy },
        credential: @escaping @MainActor (LiveProvider) async -> LiveSessionConfig.Credential? = VoicePreviewController.savedCredential,
        makeSession: @escaping SessionFactory = { config, onEvent in
            LiveSession(config: config, wire: config.provider.wire, onEvent: onEvent)
        },
        makeAudio: @escaping @MainActor () -> any LivePlaybackIO = { LiveAudioEngine() },
        notificationCenter: NotificationCenter = .default,
        waitForDeadline: @escaping @Sendable () async throws -> Void = {
            try await Task.sleep(for: .seconds(VoicePreviewController.maximumDuration))
        },
        quietFinishDelay: Duration = .milliseconds(1500)
    ) {
        self.settings = settings
        self.resolveRoute = resolveRoute ?? { VoiceAssistantRouting.current(settings: settings) }
        self.assistantIsBusy = assistantIsBusy
        self.credential = credential
        self.makeSession = makeSession
        self.makeAudio = makeAudio
        self.notificationCenter = notificationCenter
        self.waitForDeadline = waitForDeadline
        self.quietFinishDelay = quietFinishDelay
    }

    func start(appLanguageCode: String, sample: String) {
        stop()
        guard !assistantIsBusy() else {
            errorMessage = String(localized: "End your current voice conversation before previewing a voice.")
            return
        }
        guard case .ready(let provider) = resolveRoute() else {
            showFailure()
            return
        }
        phase = .preparing
        let number = generation
        // Snapshot settings before the first suspension. A view setting change
        // cancels this generation, including a pending credential lookup.
        let model = settings.model(for: provider)
        let voice = settings.voice(for: provider)
        let qwenWorkspace = settings.qwenWorkspace
        let language = Self.languageCode(settings.language, appLanguageCode: appLanguageCode)
        deadline = Task { [weak self, waitForDeadline] in
            do { try await waitForDeadline() } catch { return }
            guard let self, self.generation == number, self.isRunning else { return }
            self.showFailure()
        }
        backgroundObserver = notificationCenter.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.stop() }
        }
        preparation = Task { [weak self] in
            guard let self else { return }
            let credential = await self.credential(provider)
            guard self.generation == number, self.isRunning, !Task.isCancelled else { return }
            guard !self.assistantIsBusy(), self.resolveRoute() == .ready(provider) else {
                self.stop()
                return
            }
            guard let credential else {
                self.showFailure()
                return
            }
            let config = LiveSessionConfig(
                provider: provider, model: model, voice: voice, credential: credential,
                instructions: Self.instructions(languageCode: language), tools: [], qwenWorkspace: qwenWorkspace
            )
            self.outputSampleRate = provider.wire.outputSampleRate
            self.reportsTurnEnds = provider.reportsTurnEnds
            self.maximumAudioBytes = Int(self.outputSampleRate * 2 * Self.maximumDuration)
            let session = self.makeSession(config) { [weak self] event in
                // A serial queue hop preserves socket order: turn completion must
                // never overtake audio chunks that have not been scheduled yet.
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        self?.handle(event, generation: number, sample: sample)
                    }
                }
            }
            self.session = session
            session.connect()
        }
    }

    func stop() {
        generation += 1
        preparation?.cancel()
        preparation = nil
        deadline?.cancel()
        deadline = nil
        quietFinish?.cancel()
        quietFinish = nil
        session?.close()
        session = nil
        audio?.stop()
        audio = nil
        if let backgroundObserver { notificationCenter.removeObserver(backgroundObserver) }
        backgroundObserver = nil
        sentSample = false
        generationFinished = false
        receivedAudioBytes = 0
        phase = .idle
        errorMessage = nil
    }

    private func handle(_ event: LiveEvent, generation number: Int, sample: String) {
        guard generation == number, isRunning, !generationFinished else { return }
        switch event {
        case .ready:
            guard !sentSample else { return }
            // Start output only after setup succeeds; no recording permission is
            // requested even if a provider refuses the key, model or voice.
            let audio = makeAudio()
            self.audio = audio
            audio.onPlaybackFinished = { [weak self] in
                guard let self, self.generation == number else { return }
                self.finishIfDrained()
            }
            audio.onInterrupted = { [weak self] in
                guard self?.generation == number else { return }
                self?.stop()
            }
            do {
                try audio.startPlayback(outputSampleRate: outputSampleRate)
            } catch {
                showFailure()
                return
            }
            sentSample = true
            session?.send(contextText: sample)
            session?.requestResponse()
        case .audio(let bytes, _):
            guard sentSample, !bytes.isEmpty else { return }
            guard bytes.count.isMultiple(of: 2), bytes.count <= maximumAudioBytes - receivedAudioBytes else {
                showFailure()
                return
            }
            receivedAudioBytes += bytes.count
            phase = .playing
            audio?.play(bytes)
            if !reportsTurnEnds { scheduleQuietFinish(generation: number) }
        case .turnComplete, .responseDone(hasToolCalls: false, successful: true):
            finishGeneration()
        case .responseDone(_, true) where !reportsTurnEnds:
            // GPT-Live's backend finishes before the sample is spoken; an
            // unsuccessful one still fails below.
            break
        case .error, .hostedError, .closed, .toolCalls, .responseDone:
            showFailure()
        case .interrupted, .userSpeechStarted:
            stop()
        default:
            break
        }
    }

    private func finishGeneration() {
        guard receivedAudioBytes > 0 else {
            showFailure()
            return
        }
        generationFinished = true
        // Hosted billing ends here, independently of how long the remaining
        // locally queued PCM takes to play.
        session?.close()
        session = nil
        finishIfDrained()
    }

    /// The sample is over once audio has stopped arriving for a moment.
    private func scheduleQuietFinish(generation number: Int) {
        quietFinish?.cancel()
        quietFinish = Task { [weak self] in
            try? await Task.sleep(for: self?.quietFinishDelay ?? .milliseconds(1500))
            guard !Task.isCancelled, let self, self.generation == number, self.isRunning, !self.generationFinished else { return }
            self.finishGeneration()
        }
    }

    private func finishIfDrained() {
        guard generationFinished, audio?.isPlaying != true else { return }
        stop()
    }

    private func showFailure() {
        stop()
        // Never present/log provider text: it can contain a credential or URL.
        errorMessage = String(localized: "Couldn’t play this voice preview. Please try again.")
    }

    nonisolated static func languageCode(_ language: VoiceAssistantLanguage, appLanguageCode: String) -> String {
        if case .specific(let code) = language { return code }
        return appLanguageCode
    }

    nonisolated static func instructions(languageCode: String) -> String {
        """
        Provide a short voice preview. Say only the supplied sample in \(VoiceAssistantLanguage.englishName(for: languageCode)), translating it if needed.
        Do not add commentary, introductions, questions or sound effects. Do not call tools.
        """
    }

    private static func savedCredential(for provider: LiveProvider) async -> LiveSessionConfig.Credential? {
        if provider == .hosted {
            guard Purchases.isConfigured, RevenueCatManager.shared.hasHostedEntitlement else { return nil }
            return .hostedUser(await RevenueCatManager.shared.appUserID())
        }
        return provider.savedKey.map { .apiKey($0) }
    }
}
