import SwiftUI
import Speech
import AVFoundation

struct VoiceInputView: View {
    @State private var transcription = ""
    @State private var isRecording = false
    @State private var speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    @State private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    @State private var recognitionTask: SFSpeechRecognitionTask?
    @State private var audioEngine = AVAudioEngine()
    @State private var permissionError: String?
    @State private var pulseScale: CGFloat = 1.0

    var onCancel: () -> Void
    var onSubmit: (String) -> Void

    var body: some View {
        ZStack {
            // Semi-transparent background
            Color.black.opacity(0.4)
                .ignoresSafeArea()
                .onTapGesture {
                    stopRecording()
                    onCancel()
                }

            VStack(spacing: 24) {
                // Title
                Text("Voice Input")
                    .font(.headline)

                // Transcription area
                ZStack(alignment: .topLeading) {
                    if transcription.isEmpty && !isRecording {
                        Text("Listening for your meal…")
                            .foregroundStyle(.tertiary)
                            .font(.body)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 10)
                            .allowsHitTesting(false)
                    }

                    Text(transcription.isEmpty && isRecording ? "Listening…" : transcription)
                        .foregroundStyle(transcription.isEmpty ? .secondary : .primary)
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 10)
                }
                .padding(12)
                .frame(minHeight: 100, alignment: .topLeading)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color(.secondarySystemGroupedBackground))
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
                        .font(.system(size: 32))
                        .foregroundStyle(.white)
                        .frame(width: 80, height: 80)
                        .background(
                            Circle()
                                .fill(isRecording ? Color.red : AppColors.calorie)
                        )
                        .scaleEffect(pulseScale)
                }
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

                // Action buttons
                HStack(spacing: 16) {
                    Button("Cancel", role: .cancel) {
                        stopRecording()
                        onCancel()
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)

                    Button("Analyze") {
                        stopRecording()
                        onSubmit(transcription)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .controlSize(.large)
                    .disabled(transcription.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .padding(24)
            .background(
                RoundedRectangle(cornerRadius: 20)
                    .fill(Color(.systemGroupedBackground))
            )
            .padding(.horizontal, 24)
        }
        .onAppear {
            startRecording()
        }
        .onDisappear {
            stopRecording()
        }
    }

    private func startRecording() {
        permissionError = nil

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
                beginAudioSession()
            }
        }
    }

    private func beginAudioSession() {
        guard let speechRecognizer, speechRecognizer.isAvailable else {
            permissionError = "Speech recognition is not available on this device."
            return
        }

        // Cancel any existing task
        recognitionTask?.cancel()
        recognitionTask = nil

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        recognitionRequest = request

        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.record, mode: .measurement, options: .duckOthers)
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            permissionError = "Failed to set up audio session."
            return
        }

        let inputNode = audioEngine.inputNode
        // Remove existing taps
        inputNode.removeTap(onBus: 0)

        let recordingFormat = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
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
            if let result {
                transcription = result.bestTranscription.formattedString
            }
            if error != nil || (result?.isFinal ?? false) {
                stopRecording()
            }
        }
    }

    private func stopRecording() {
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
}
