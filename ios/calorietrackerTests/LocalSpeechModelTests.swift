import Foundation
import Testing
@testable import calorietracker

struct LocalSpeechModelTests {
    @Test func tiersAndDownloadSizesAreSeparate() {
        let gib: UInt64 = 1_073_741_824
        #expect(!LocalSpeechModel.parakeetV3.isEligible(memory: 4 * gib))
        #expect(LocalSpeechModel.parakeetV3.isEligible(memory: 6 * gib))
        #expect(!LocalSpeechModel.qwen3ASR.isEligible(memory: 6 * gib))
        #expect(LocalSpeechModel.qwen3ASR.isEligible(memory: 8 * gib))
        #expect(!Gemma4LocalModelManager.isEligible(physicalMemoryBytes: 8 * gib, variant: .e4b))
        #expect(Gemma4LocalModelManager.isEligible(physicalMemoryBytes: 12 * gib, variant: .e4b))
        #expect(LocalSpeechModel.parakeetV3.downloadBytes == 670_576_784)
        #expect(LocalSpeechModel.qwen3ASR.downloadBytes == 987_015_347)
        #expect(Gemma4LocalModelManager.Variant.e4b.byteCount == 3_659_530_240)
    }

    @Test func aMultipartModelIsNotReadyUntilEveryFileIsVerified() throws {
        let model = LocalSpeechModel.qwen3ASR
        let fm = FileManager.default
        let root = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? fm.removeItem(at: root) }
        for file in model.files {
            #expect(!LocalSpeechModelManager.hasCompleteInstall(model, directory: root))
            let destination = root.appendingPathComponent(file.path)
            try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data().write(to: destination)
            let handle = try FileHandle(forWritingTo: destination)
            try handle.truncate(atOffset: UInt64(file.bytes))
            try handle.close()
            try Data(file.hash.utf8).write(to: destination.appendingPathExtension("sha256"))
        }
        #expect(LocalSpeechModelManager.hasCompleteInstall(model, directory: root))
        try fm.removeItem(at: root.appendingPathComponent("tokenizer/merges.txt"))
        #expect(!LocalSpeechModelManager.hasCompleteInstall(model, directory: root))
    }

    @Test func longDictationKeepsAllAudioAndSplitsAtAPause() {
        var samples = Array(repeating: Float(0.5), count: 45 * 16_000)
        for index in (18 * 16_000)..<(18 * 16_000 + 640) { samples[index] = 0 }
        let chunks = LocalSpeechModelManager.chunks(samples)
        #expect(chunks.first?.lowerBound == 0)
        #expect(chunks.first.map { (18 * 16_000...18 * 16_000 + 640).contains($0.upperBound) } == true)
        #expect(chunks.last?.upperBound == samples.count)
        #expect(zip(chunks, chunks.dropFirst()).allSatisfy { $0.0.upperBound == $0.1.lowerBound })
        #expect(chunks.allSatisfy { $0.count <= 320_000 })
    }
}
