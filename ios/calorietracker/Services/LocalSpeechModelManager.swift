import AVFAudio
import Foundation
import Observation
import SherpaOnnxC
import WhisperKit

nonisolated enum LocalSpeechModel: String, CaseIterable, Identifiable, Sendable {
    case parakeetV3, qwen3ASR

    struct File: Sendable {
        let path: String
        let bytes: Int64
        let hash: String

        init(_ path: String, _ bytes: Int64, _ hash: String) {
            self.path = path
            self.bytes = bytes
            self.hash = hash
        }
    }

    var id: Self { self }
    var name: String { self == .parakeetV3 ? "Parakeet v3" : "Qwen3-ASR 0.6B" }
    var minimumRAM: UInt64 { self == .parakeetV3 ? 6 : 8 }
    var repository: String {
        self == .parakeetV3
            ? "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
            : "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    }
    var revision: String {
        self == .parakeetV3 ? "ed5d745623472f97cfc2e6c2916ee248798fc5a8" : "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
    }
    var source: URL { URL(string: "https://huggingface.co/\(repository)/tree/\(revision)")! }
    var licenseName: String { self == .parakeetV3 ? "CC BY 4.0" : "Apache 2.0" }
    var licenseURL: URL {
        URL(string: self == .parakeetV3
            ? "https://creativecommons.org/licenses/by/4.0/"
            : "https://github.com/QwenLM/Qwen3-ASR/blob/main/LICENSE")!
    }
    var files: [File] {
        switch self {
        case .parakeetV3: [
            File("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
            File("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
            File("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
            File("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")
        ]
        case .qwen3ASR: [
            File("encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            File("conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            File("decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            File("tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            File("tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
            File("tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910")
        ]
        }
    }
    var downloadBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }
    func url(for file: File) -> URL { URL(string: "https://huggingface.co/\(repository)/resolve/\(revision)/\(file.path)")! }
    func isEligible(memory: UInt64 = ProcessInfo.processInfo.physicalMemory) -> Bool {
        Gemma4LocalModelManager.memoryClassGB(physicalMemoryBytes: memory) >= minimumRAM
    }
}

@MainActor
@Observable
final class LocalSpeechModelManager {
    static let parakeet = LocalSpeechModelManager(model: .parakeetV3)
    static let qwen = LocalSpeechModelManager(model: .qwen3ASR)

    static func forModel(_ model: LocalSpeechModel) -> LocalSpeechModelManager {
        model == .parakeetV3 ? parakeet : qwen
    }

    let model: LocalSpeechModel
    let storageDirectory: URL
    private(set) var progress: Double?
    private(set) var isInstalled = false
    private(set) var hasFiles = false
    private(set) var errorMessage: String?
    private var activeDownload: GemmaArtifactDownloadOperation?
    private var cancelled = false
    private static var isTranscribing = false

    private init(model: LocalSpeechModel) {
        self.model = model
        storageDirectory = Self.directory(for: model)
        refresh()
    }

    private static func directory(for model: LocalSpeechModel) -> URL {
        URL.applicationSupportDirectory
            .appendingPathComponent("FudAI/LocalModels", isDirectory: true)
            .appendingPathComponent(model.rawValue, isDirectory: true)
    }

    static func isSelectable(_ model: LocalSpeechModel) -> Bool {
        model.isEligible() && hasCompleteInstall(model, directory: directory(for: model))
    }

    nonisolated static func hasCompleteInstall(_ model: LocalSpeechModel, directory: URL) -> Bool {
        model.files.allSatisfy { isVerified($0, directory: directory) }
    }

    nonisolated private static func isVerified(_ file: LocalSpeechModel.File, directory: URL) -> Bool {
        let destination = directory.appendingPathComponent(file.path)
        return Gemma4LocalModelManager.hasVerifiedInstall(
            modelURL: destination,
            verificationMarkerURL: destination.appendingPathExtension("sha256"),
            expectedByteCount: file.bytes,
            expectedSHA256: file.hash,
            fileManager: .default
        )
    }

    func refresh() {
        isInstalled = Self.hasCompleteInstall(model, directory: storageDirectory)
        hasFiles = FileManager.default.fileExists(atPath: storageDirectory.path)
    }

    func download() async {
        guard progress == nil, model.isEligible(), !isInstalled else { return }
        let fm = FileManager.default
        var root = storageDirectory
        progress = 0
        errorMessage = nil
        cancelled = false
        defer {
            activeDownload = nil
            progress = nil
            refresh()
        }
        do {
            try fm.createDirectory(at: root, withIntermediateDirectories: true)
            var resourceValues = URLResourceValues()
            resourceValues.isExcludedFromBackup = true
            try root.setResourceValues(resourceValues)
            let needed = model.files.reduce(Int64(0)) { total, file in
                guard !Self.isVerified(file, directory: root) else { return total }
                let partial = root.appendingPathComponent("\(file.hash).partial")
                let size = (try? partial.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                return total + file.bytes - (Int64(size) <= file.bytes ? Int64(size) : 0)
            }
            let available = try root.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage
            guard let available, available >= needed + 1_024 * 1_024 * 1_024 else {
                throw LocalSpeechError.storage
            }
            var completeBytes: Int64 = 0
            for file in model.files {
                try Task.checkCancellation()
                guard !cancelled else { throw CancellationError() }
                if !Self.isVerified(file, directory: root) {
                    try await download(file, completeBytes: completeBytes)
                }
                completeBytes += file.bytes
            }
        } catch {
            if !cancelled && !(error is CancellationError) { errorMessage = error.localizedDescription }
        }
    }

    private func download(_ file: LocalSpeechModel.File, completeBytes: Int64) async throws {
        let fm = FileManager.default
        let destination = storageDirectory.appendingPathComponent(file.path)
        let partial = storageDirectory.appendingPathComponent("\(file.hash).partial")
        let size = (try? partial.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        if Int64(size) > file.bytes { try fm.removeItem(at: partial) }
        if Int64(size) != file.bytes {
            let totalBytes = Double(model.downloadBytes)
            let task = GemmaArtifactDownloadOperation(
                fileURL: partial, expectedByteCount: file.bytes, fileManager: fm
            ) { [weak self] fraction in
                Task { @MainActor in
                    if self?.progress != nil {
                        self?.progress = (Double(completeBytes) + fraction * Double(file.bytes)) / totalBytes
                    }
                }
            }
            activeDownload = task
            try await task.start(url: model.url(for: file))
            activeDownload = nil
        }
        let verified = try await Task.detached(priority: .utility) {
            try Gemma4LocalModelManager.verifyArtifact(at: partial)
        }.value
        guard !cancelled else { throw CancellationError() }
        guard verified.byteCount == file.bytes, verified.sha256 == file.hash else {
            try? fm.removeItem(at: partial)
            throw LocalSpeechError.invalidDownload
        }
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        if fm.fileExists(atPath: destination.path) { try fm.removeItem(at: destination) }
        try fm.moveItem(at: partial, to: destination)
        try Data(file.hash.utf8).write(to: destination.appendingPathExtension("sha256"), options: .atomic)
    }

    func cancel() {
        cancelled = true
        activeDownload?.cancel()
    }

    func delete() {
        guard progress == nil, !Self.isTranscribing else { return }
        do {
            try FileManager.default.removeItem(at: storageDirectory)
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
        refresh()
    }

    func transcribe(_ audio: URL) async throws -> String {
        guard model.isEligible(), isInstalled else { throw LocalSpeechError.unavailable }
        guard !Self.isTranscribing else { throw LocalSpeechError.busy }
        Self.isTranscribing = true
        defer { Self.isTranscribing = false }
        let model = model
        let directory = storageDirectory
        let task = Task.detached(priority: .userInitiated) {
            try Self.decode(audio, model: model, directory: directory)
        }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }

    nonisolated private static func decode(_ audio: URL, model: LocalSpeechModel, directory: URL) throws -> String {
        let buffer = try AudioProcessor.loadAudio(fromPath: audio.path)
        let samples = AudioProcessor.convertBufferToArray(buffer: buffer)
        guard buffer.format.sampleRate == 16_000, !samples.isEmpty else { throw LocalSpeechError.emptyTranscript }

        func path(_ name: String) -> String { directory.appendingPathComponent(name).path }
        var pointers: [UnsafeMutablePointer<CChar>] = []
        defer { for pointer in pointers { free(pointer) } }
        func cString(_ text: String) -> UnsafePointer<CChar>? {
            guard let pointer = strdup(text) else { return nil }
            pointers.append(pointer)
            return UnsafePointer(pointer)
        }

        var config = SherpaOnnxOfflineRecognizerConfig()
        config.feat_config.sample_rate = 16_000
        config.feat_config.feature_dim = 80
        config.model_config.num_threads = Int32(min(4, ProcessInfo.processInfo.activeProcessorCount))
        config.model_config.provider = cString("cpu")
        config.decoding_method = cString("greedy_search")
        if model == .parakeetV3 {
            config.model_config.tokens = cString(path("tokens.txt"))
            config.model_config.transducer.encoder = cString(path("encoder.int8.onnx"))
            config.model_config.transducer.decoder = cString(path("decoder.int8.onnx"))
            config.model_config.transducer.joiner = cString(path("joiner.int8.onnx"))
        } else {
            config.model_config.qwen3_asr.conv_frontend = cString(path("conv_frontend.onnx"))
            config.model_config.qwen3_asr.encoder = cString(path("encoder.int8.onnx"))
            config.model_config.qwen3_asr.decoder = cString(path("decoder.int8.onnx"))
            config.model_config.qwen3_asr.tokenizer = cString(path("tokenizer"))
            config.model_config.qwen3_asr.max_total_len = 512
            config.model_config.qwen3_asr.max_new_tokens = 256
            config.model_config.qwen3_asr.temperature = 1e-6
            config.model_config.qwen3_asr.top_p = 0.8
            config.model_config.qwen3_asr.seed = 42
        }
        try Task.checkCancellation()
        guard let recognizer = SherpaOnnxCreateOfflineRecognizer(&config) else { throw LocalSpeechError.unavailable }
        defer { SherpaOnnxDestroyOfflineRecognizer(recognizer) }
        let transcript = try chunks(samples).compactMap { range -> String? in
            try Task.checkCancellation()
            guard let stream = SherpaOnnxCreateOfflineStream(recognizer) else { throw LocalSpeechError.unavailable }
            defer { SherpaOnnxDestroyOfflineStream(stream) }
            samples.withUnsafeBufferPointer { data in
                SherpaOnnxAcceptWaveformOffline(stream, 16_000, data.baseAddress?.advanced(by: range.lowerBound), Int32(range.count))
            }
            SherpaOnnxDecodeOfflineStream(recognizer, stream)
            guard let result = SherpaOnnxGetOfflineStreamResult(stream) else { throw LocalSpeechError.unavailable }
            defer { SherpaOnnxDestroyOfflineRecognizerResult(result) }
            guard let raw = result.pointee.text else { return nil }
            let text = String(cString: raw).trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
        }.joined(separator: " ")
        try Task.checkCancellation()
        guard !transcript.isEmpty else { throw LocalSpeechError.emptyTranscript }
        return transcript
    }

    nonisolated static func chunks(_ samples: [Float]) -> [Range<Int>] {
        var output: [Range<Int>] = []
        var start = 0
        let window = 320
        while start < samples.count {
            let last = min(start + 320_000, samples.count)
            var end = last
            if last < samples.count {
                var quietest = Double.infinity
                for candidate in stride(from: last - window, through: last - 48_000, by: -window) {
                    let energy = samples[candidate..<(candidate + window)].reduce(0.0) {
                        $0 + Double($1) * Double($1)
                    }
                    if energy < quietest {
                        quietest = energy
                        end = candidate + window / 2
                    }
                }
            }
            output.append(start..<end)
            start = end
        }
        return output
    }

    nonisolated enum LocalSpeechError: LocalizedError {
        case storage, invalidDownload, unavailable, busy, emptyTranscript

        var errorDescription: String? {
            switch self {
            case .storage: String(localized: "Not enough free storage for this speech model.")
            case .invalidDownload: String(localized: "The speech model download could not be verified.")
            case .unavailable: String(localized: "This speech model is not ready. Check its download in Settings → Speech-to-Text.")
            case .busy: String(localized: "A local transcription is already in progress.")
            case .emptyTranscript: String(localized: "No speech was detected in this recording.")
            }
        }
    }
}
