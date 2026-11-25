import Foundation

/// Removes only the retired LFM3 download and its private temporary audio.
nonisolated enum RetiredOfflineVoiceFiles {
    static func cleanUp(applicationSupport: URL = .applicationSupportDirectory,
                        temporary: URL = FileManager.default.temporaryDirectory) {
        let model = applicationSupport.appendingPathComponent("FudAI/LocalModels/lfm3Audio", isDirectory: true)
        let audio = temporary.appendingPathComponent("FudOfflineVoice", isDirectory: true)
        for folder in [model, audio] {
            try? FileManager.default.removeItem(at: folder)
        }
    }
}
