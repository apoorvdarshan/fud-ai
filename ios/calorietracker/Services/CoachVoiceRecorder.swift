import SwiftUI
import Speech
import AVFoundation

/// Drives the WhatsApp-style inline voice recorder in the Coach input bar.
///
///  - `begin()` on press-down starts recording (phase `.holding`).
///  - Release sends (`stopAndSend()`); slide-left past the threshold cancels.
///  - A quick tap `lock()`s into hands-free recording with explicit Send / Cancel.
///
/// Honors the user's configured STT provider (`SpeechSettings.selectedProvider`):
/// native streams via `SFSpeechRecognizer` with live partial text; local Whisper
/// and remote providers record to an m4a and transcribe on stop via `SpeechService`.
@Observable
final class CoachVoiceRecorder {
    enum Phase { case idle, holding, locked, transcribing }

    var phase: Phase = .idle
    var elapsed: TimeInterval = 0
    var liveText = ""
    var cancelArmed = false
    var errorMessage: String?
    /// Set to the final transcript when a recording completes; the view sends it
    /// and clears this back to nil.
    var submittedTranscript: String?

    var isRecording: Bool { phase == .holding || phase == .locked }

    private var provider: SpeechProvider { SpeechSettings.selectedProvider }
    private var isNative: Bool { provider == .nativeIOS }

    // Native (streaming, on-device)
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()

    // Remote (record to file, upload on stop)
    private var audioRecorder: AVAudioRecorder?
    private var recordedFileURL: URL?

    private var timer: Timer?
    private var startDate: Date?

    // MARK: - Gesture entry points

    func begin() {
        guard phase == .idle else { return }
        phase = .holding
        cancelArmed = false
        liveText = ""
        errorMessage = nil
        elapsed = 0
        startTimer()
        if isNative {
            startNative()
        } else {
            guard !provider.requiresAPIKey || SpeechSettings.apiKey(for: provider) != nil else {
                fail("No API key for \(provider.displayName). Add one in Settings → Speech-to-Text.")
                return
            }
            startRemote()
        }
    }

    func lock() {
        if phase == .holding {
            phase = .locked
            cancelArmed = false
        }
    }

    func updateDrag(_ dx: CGFloat, threshold: CGFloat) {
        if phase == .holding { cancelArmed = dx < -threshold }
    }

    func cancel() {
        stopTimer()
        if isNative { teardownNative() } else { teardownRemote(discard: true) }
        reset()
    }

    func stopAndSend() {
        stopTimer()
        if isNative {
            teardownNative()
            let text = liveText.trimmingCharacters(in: .whitespacesAndNewlines)
            reset()
            if !text.isEmpty { submittedTranscript = text }
        } else {
            phase = .transcribing
            transcribeRemote()
        }
    }

    // MARK: - Helpers

    private func reset() {
        phase = .idle
        elapsed = 0
        liveText = ""
        cancelArmed = false
    }

    private func fail(_ message: String) {
        errorMessage = message
        stopTimer()
        reset()
    }

    private func startTimer() {
        startDate = Date()
        elapsed = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self, let start = self.startDate else { return }
            self.elapsed = Date().timeIntervalSince(start)
        }
    }

    private func stopTimer() {
        timer?.invalidate()
        timer = nil
        startDate = nil
    }

    // MARK: - Native

    private func startNative() {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                guard status == .authorized else {
                    self.fail("Speech recognition permission denied. Enable it in Settings.")
                    return
                }
                AVAudioApplication.requestRecordPermission { allowed in
                    DispatchQueue.main.async {
                        guard allowed else {
                            self.fail("Microphone permission denied. Enable it in Settings.")
                            return
                        }
                        // The gesture may have already ended (cancel / immediate send).
                        guard self.isRecording else { return }
                        self.beginNativeSession()
                    }
                }
            }
        }
    }

    private func beginNativeSession() {
        speechRecognizer = Self.makeRecognizer(for: SpeechSettings.selectedLanguage(for: .nativeIOS))
        guard let speechRecognizer, speechRecognizer.isAvailable else {
            fail("Native speech recognition is unavailable on this device.")
            return
        }

        recognitionTask?.cancel()
        recognitionTask = nil

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        recognitionRequest = request

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            fail("Failed to set up the audio session.")
            return
        }

        let inputNode = audioEngine.inputNode
        inputNode.removeTap(onBus: 0)
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.recognitionRequest?.append(buffer)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            fail("Failed to start the audio engine.")
            return
        }

        recognitionTask = speechRecognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self else { return }
            if let result { self.liveText = result.bestTranscription.formattedString }
            // Ignore the recognizer's own end-of-utterance; the user's release /
            // send / cancel drives stopping so a mid-sentence pause won't cut off.
            if error != nil { self.recognitionTask = nil }
        }
    }

    private func teardownNative() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        recognitionTask?.cancel()
        recognitionTask = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Remote

    private func startRemote() {
        AVAudioApplication.requestRecordPermission { allowed in
            DispatchQueue.main.async {
                guard allowed else {
                    self.fail("Microphone permission denied. Enable it in Settings.")
                    return
                }
                guard self.isRecording else { return }
                self.beginRemoteRecording()
            }
        }
    }

    private func beginRemoteRecording() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .default, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            fail("Failed to set up the audio session.")
            return
        }

        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-voice-\(UUID().uuidString).m4a")
        recordedFileURL = fileURL

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
        do {
            audioRecorder = try AVAudioRecorder(url: fileURL, settings: settings)
            audioRecorder?.record()
        } catch {
            fail("Failed to start recording: \(error.localizedDescription)")
        }
    }

    private func teardownRemote(discard: Bool) {
        audioRecorder?.stop()
        audioRecorder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if discard, let url = recordedFileURL {
            try? FileManager.default.removeItem(at: url)
            recordedFileURL = nil
        }
    }

    private func transcribeRemote() {
        audioRecorder?.stop()
        audioRecorder = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        guard let fileURL = recordedFileURL else { reset(); return }
        recordedFileURL = nil
        let capturedProvider = provider

        Task { @MainActor in
            var text = ""
            do {
                text = try await SpeechService.transcribe(audioURL: fileURL, capturedProvider: capturedProvider)
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            try? FileManager.default.removeItem(at: fileURL)
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            reset()
            if !trimmed.isEmpty { submittedTranscript = trimmed }
        }
    }

    // MARK: - Locale selection (mirrors VoiceInputView)

    private static func makeRecognizer(for language: SpeechLanguage) -> SFSpeechRecognizer? {
        let supported = SFSpeechRecognizer.supportedLocales()
        let preferred = language.preferredNativeLocale
        return SFSpeechRecognizer(locale: resolvedLocale(preferred, from: supported))
    }

    private static func resolvedLocale(_ preferred: Locale, from supported: Set<Locale>) -> Locale {
        func normalized(_ id: String) -> String { id.replacingOccurrences(of: "_", with: "-").lowercased() }
        if let exact = supported.first(where: { normalized($0.identifier) == normalized(preferred.identifier) }) {
            return exact
        }
        if let code = preferred.language.languageCode?.identifier.lowercased(),
           let match = supported.first(where: { $0.language.languageCode?.identifier.lowercased() == code }) {
            return match
        }
        return Locale(identifier: "en-US")
    }
}

/// Formats an elapsed interval as m:ss for the recording timer.
func formatVoiceElapsed(_ seconds: TimeInterval) -> String {
    let total = Int(seconds)
    return String(format: "%d:%02d", total / 60, total % 60)
}
