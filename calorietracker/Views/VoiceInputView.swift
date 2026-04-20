import SwiftUI
import Speech
import AVFoundation

/// Voice input that branches based on the user's selected Speech-to-Text provider:
/// - Native iOS → live SFSpeechRecognizer streaming with partial results (original behavior)
/// - Remote providers (OpenAI / Groq / Deepgram / AssemblyAI) → record to an m4a file, upload on stop, show transcription when it returns
struct VoiceInputView: View {
    @State private var transcription = ""
    @State private var isRecording = false
    @State private var isTranscribing = false
    @State private var permissionError: String?
    @State private var pulseScale: CGFloat = 1.0

    // Native path
    @State private var speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    @State private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    @State private var recognitionTask: SFSpeechRecognitionTask?
    @State private var audioEngine = AVAudioEngine()

    // Remote path (file-based recorder)
    @State private var audioRecorder: AVAudioRecorder?
    @State private var recordedFileURL: URL?

    var onCancel: () -> Void
    var onSubmit: (String) -> Void

    private var provider: SpeechProvider { SpeechSettings.selectedProvider }
    private var isNative: Bool { provider == .nativeIOS }

    var body: some View {
        VStack(spacing: 20) {
            // Provider badge
            HStack(spacing: 6) {
                Image(systemName: provider.icon)
                    .font(.system(size: 11, weight: .semibold))
                Text(provider.rawValue)
                    .font(.system(.caption2, design: .rounded, weight: .medium))
            }
            .foregroundStyle(AppColors.calorie)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Capsule().fill(AppColors.calorie.opacity(0.12)))

            // Transcription area
            ZStack(alignment: .topLeading) {
                if transcription.isEmpty && !isTranscribing {
                    Text(isRecording ? "Listening…" : "Tap the mic to start")
                        .foregroundStyle(.tertiary)
                        .font(.body)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 10)
                        .allowsHitTesting(false)
                }

                if isTranscribing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Transcribing via \(provider.rawValue)…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 6)
                    .padding(.vertical, 10)
                }

                Text(transcription)
                    .font(.body)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 10)
            }
            .padding(12)
            .frame(minHeight: 100, alignment: .topLeading)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.quaternarySystemFill))
            )

            // Mic button
            Button {
                if isRecording {
                    stopRecording()
                } else {
                    startRecording()
                }
            } label: {
                Image(systemName: isRecording ? "mic.fill" : "mic")
                    .font(.system(size: 28))
                    .foregroundStyle(.white)
                    .frame(width: 72, height: 72)
                    .background(
                        Circle()
                            .fill(isRecording ? Color.red : AppColors.calorie)
                    )
                    .scaleEffect(pulseScale)
            }
            .disabled(isTranscribing)
            .onChange(of: isRecording) { _, recording in
                if recording {
                    withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) {
                        pulseScale = 1.15
                    }
                } else {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        pulseScale = 1.0
                    }
                }
            }

            if let error = permissionError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }

            // Analyze button
            Button {
                stopRecording()
                onSubmit(transcription)
            } label: {
                Text("Analyze")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .controlSize(.large)
            .disabled(transcription.trimmingCharacters(in: .whitespaces).isEmpty || isRecording || isTranscribing)

            Button("Cancel") {
                stopRecording()
                onCancel()
            }
            .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(width: 320)
        .onAppear { startRecording() }
        .onDisappear { stopRecording() }
    }

    // MARK: - Start / Stop dispatch

    private func startRecording() {
        permissionError = nil
        transcription = ""
        if isNative {
            startNativeRecording()
        } else {
            guard SpeechSettings.apiKey(for: provider) != nil else {
                permissionError = "No API key configured for \(provider.rawValue). Add one in Settings → Speech-to-Text."
                return
            }
            startRemoteRecording()
        }
    }

    private func stopRecording() {
        if isNative {
            stopNativeRecording()
        } else {
            stopRemoteRecording()
        }
    }

    // MARK: - Native (streaming, on-device)

    private func startNativeRecording() {
        SFSpeechRecognizer.requestAuthorization { authStatus in
            guard authStatus == .authorized else {
                permissionError = "Speech recognition permission denied. Enable it in Settings."
                return
            }
            AVAudioApplication.requestRecordPermission { allowed in
                guard allowed else {
                    permissionError = "Microphone permission denied. Enable it in Settings."
                    return
                }
                beginNativeAudioSession()
            }
        }
    }

    private func beginNativeAudioSession() {
        guard let speechRecognizer, speechRecognizer.isAvailable else {
            permissionError = "Native speech recognition unavailable on this device."
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
            permissionError = "Failed to set up audio session."
            return
        }

        let inputNode = audioEngine.inputNode
        inputNode.removeTap(onBus: 0)
        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            recognitionRequest?.append(buffer)
        }

        audioEngine.prepare()
        do {
            try audioEngine.start()
            isRecording = true
        } catch {
            permissionError = "Failed to start audio engine."
            return
        }

        recognitionTask = speechRecognizer.recognitionTask(with: request) { result, error in
            if let result { transcription = result.bestTranscription.formattedString }
            if error != nil || (result?.isFinal ?? false) { stopNativeRecording() }
        }
    }

    private func stopNativeRecording() {
        guard isRecording else { return }
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionRequest = nil
        recognitionTask?.cancel()
        recognitionTask = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - Remote (record to file, upload on stop)

    private func startRemoteRecording() {
        AVAudioApplication.requestRecordPermission { allowed in
            guard allowed else {
                permissionError = "Microphone permission denied. Enable it in Settings."
                return
            }
            beginRemoteRecording()
        }
    }

    private func beginRemoteRecording() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .default, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            permissionError = "Failed to set up audio session."
            return
        }

        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
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
            isRecording = true
        } catch {
            permissionError = "Failed to start recording: \(error.localizedDescription)"
        }
    }

    private func stopRemoteRecording() {
        guard isRecording || audioRecorder != nil else { return }
        audioRecorder?.stop()
        audioRecorder = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

        guard let fileURL = recordedFileURL else { return }
        recordedFileURL = nil

        isTranscribing = true
        Task {
            defer { isTranscribing = false }
            do {
                let text = try await SpeechService.transcribe(audioURL: fileURL)
                transcription = text
            } catch {
                permissionError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
            try? FileManager.default.removeItem(at: fileURL)
        }
    }
}
