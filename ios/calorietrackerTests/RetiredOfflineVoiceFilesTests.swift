import Foundation
import Testing
@testable import calorietracker

struct RetiredOfflineVoiceFilesTests {
    @Test func cleanupRemovesOnlyLfm3AndItsTemporaryAudio() throws {
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? fm.removeItem(at: root) }
        let support = root.appendingPathComponent("support", isDirectory: true)
        let temp = root.appendingPathComponent("tmp", isDirectory: true)
        func fixture(_ base: URL, _ name: String) throws -> URL {
            let file = base.appendingPathComponent(name)
            try fm.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data("test".utf8).write(to: file)
            return file
        }
        let model = try fixture(support, "FudAI/LocalModels/lfm3Audio/model.gguf")
        let partial = try fixture(support, "FudAI/LocalModels/lfm3Audio/codec.partial")
        let audio = try fixture(temp, "FudOfflineVoice/recording.wav")
        let qwen = try fixture(support, "FudAI/LocalModels/qwen3ASR/encoder.int8.onnx")
        let other = try fixture(temp, "other-recording.wav")

        for _ in 0..<2 { RetiredOfflineVoiceFiles.cleanUp(applicationSupport: support, temporary: temp) }
        for file in [model, partial, audio] { #expect(!fm.fileExists(atPath: file.path)) }
        for file in [qwen, other] { #expect(try Data(contentsOf: file) == Data("test".utf8)) }
    }
}
