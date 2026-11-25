import AVFAudio
import Foundation
import Observation

// Only cancellation runs concurrently with the native call. The task and its
// cancellation handler retain this owner until both return.
private final class OfflineVoiceTurn: @unchecked Sendable {
    nonisolated let handle: OpaquePointer
    nonisolated init?() {
        guard let handle = fud_voice_create() else { return nil }
        self.handle = handle
    }
    deinit { fud_voice_destroy(handle) }
    nonisolated func cancel() { fud_voice_cancel(handle) }
    nonisolated func reply(model: URL, projector: URL, input: URL, output: URL) -> Int32 {
        fud_voice_reply(handle, model.path, projector.path, input.path, output.path, true)
    }
    nonisolated func agentReply(model: URL, projector: URL, input: URL, output: URL,
                                tools: String, continuation: String, text: URL) -> Int32 {
        fud_voice_agent_reply(handle, model.path, projector.path, input.path, output.path, true,
                              tools, continuation, text.path)
    }
}

@MainActor
@Observable
final class OfflineVoiceController: NSObject, AVAudioPlayerDelegate {
    enum Phase { case idle, requesting, recording, working, speaking }
    enum Failure { case microphone, recording, reply }

    private(set) var phase = Phase.idle
    private(set) var failure: Failure?
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var work: Task<Void, Never>?
    private var recordLimit: Task<Void, Never>?
    private var sessionActive = false
    private var epoch = 0
    typealias Generate = @MainActor (String, String) async throws -> String
    var agent: (@MainActor (Generate, () -> Bool) async throws -> Void)?

    var isBusy: Bool { phase != .idle || work != nil }

    func start() {
        guard !isBusy, LocalSpeechModelManager.isSelectable(.lfm3Audio) else { return }
        failure = nil
        phase = .requesting
        epoch += 1
        let request = epoch
        // The OS permission prompt can temporarily make the scene inactive.
        Task {
            let allowed = await AVAudioApplication.requestRecordPermission()
            guard request == epoch else { return }
            guard allowed else {
                phase = .idle
                failure = .microphone
                return
            }
            do {
                let url = try Self.newAudioURL()
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
                try session.setActive(true)
                sessionActive = true
                let recorder = try AVAudioRecorder(url: url, settings: [
                    AVFormatIDKey: kAudioFormatLinearPCM,
                    AVSampleRateKey: 16_000,
                    AVNumberOfChannelsKey: 1,
                    AVLinearPCMBitDepthKey: 16
                ])
                self.recorder = recorder
                guard recorder.record(forDuration: 10) else { throw CocoaError(.fileWriteUnknown) }
                phase = .recording
                recordLimit = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(10))
                    if !Task.isCancelled { self?.send() }
                }
            } catch {
                stop()
                failure = .recording
            }
        }
    }

    func send() {
        guard phase == .recording, let recorder else { return }
        recordLimit?.cancel()
        recordLimit = nil
        recorder.stop()
        self.recorder = nil
        let input = recorder.url
        let output = input.deletingLastPathComponent().appendingPathComponent("\(UUID().uuidString).wav")
        let request = epoch
        phase = .working
        closeAudioSession()
        work = Task {
            defer {
                work = nil
                try? FileManager.default.removeItem(at: input)
                try? FileManager.default.removeItem(at: output)
            }
            do {
                guard let turn = OfflineVoiceTurn() else { throw CocoaError(.fileReadUnknown) }
                let code = try await withTaskCancellationHandler {
                    if let agent {
                        let text = output.appendingPathExtension("txt")
                        defer { try? FileManager.default.removeItem(at: text) }
                        try await agent({ tools, continuation in
                            try Task.checkCancellation()
                            if FileManager.default.fileExists(atPath: output.path) { try FileManager.default.removeItem(at: output) }
                            let status = try await LocalSpeechModelManager.lfm3.useVerifiedFiles { directory in
                                let files = LocalSpeechModel.lfm3Audio.files.map { directory.appendingPathComponent($0.path) }
                                return await Task.detached(priority: .userInitiated) {
                                    turn.agentReply(model: files[0], projector: files[1], input: input, output: output,
                                                    tools: tools, continuation: continuation, text: text)
                                }.value
                            }
                            guard status == 0 else { throw CocoaError(.fileReadUnknown) }
                            return try String(contentsOf: text, encoding: .utf8)
                        }, { FileManager.default.fileExists(atPath: output.path) })
                        if !FileManager.default.fileExists(atPath: output.path) {
                            if request == epoch { phase = .idle }
                            return Int32(-1)
                        }
                        return Int32(0)
                    }
                    return try await LocalSpeechModelManager.lfm3.useVerifiedFiles { directory in
                        let files = LocalSpeechModel.lfm3Audio.files.map { directory.appendingPathComponent($0.path) }
                        return await Task.detached(priority: .userInitiated) {
                            turn.reply(model: files[0], projector: files[1], input: input, output: output)
                        }.value
                    }
                } onCancel: { turn.cancel() }
                guard request == epoch, !Task.isCancelled else { return }
                if code == -1 { return }
                guard code == 0 else {
                    phase = .idle
                    failure = code == 4 ? .recording : .reply
                    return
                }
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .spokenAudio)
                try session.setActive(true)
                sessionActive = true
                let audio = try Data(contentsOf: output)
                let player = try AVAudioPlayer(data: audio)
                player.delegate = self
                self.player = player
                guard player.play() else { throw CocoaError(.fileReadUnknown) }
                phase = .speaking
            } catch {
                guard request == epoch, !Task.isCancelled else { return }
                stop()
                failure = .reply
            }
        }
    }

    func stop() {
        epoch += 1
        work?.cancel()
        recordLimit?.cancel()
        recordLimit = nil
        if let recorder {
            recorder.stop()
            recorder.deleteRecording()
            self.recorder = nil
        }
        player?.stop()
        player = nil
        phase = .idle
        closeAudioSession()
    }

    private func closeAudioSession() {
        guard sessionActive else { return }
        sessionActive = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private static var audioDirectory = FileManager.default.temporaryDirectory
        .appendingPathComponent("FudOfflineVoice", isDirectory: true)
    private static var discardedOldAudio = false

    private static func newAudioURL() throws -> URL {
        let fm = FileManager.default
        if !discardedOldAudio {
            if fm.fileExists(atPath: audioDirectory.path) { try fm.removeItem(at: audioDirectory) }
            discardedOldAudio = true
        }
        try fm.createDirectory(at: audioDirectory, withIntermediateDirectories: true)
        return audioDirectory.appendingPathComponent("\(UUID().uuidString).wav")
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        let identity = ObjectIdentifier(player)
        Task { @MainActor in
            guard let current = self.player, ObjectIdentifier(current) == identity else { return }
            stop()
            if !flag { failure = .reply }
        }
    }
}
