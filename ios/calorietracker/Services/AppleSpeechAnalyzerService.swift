import AVFAudio
import Foundation
import Speech

/// Explicitly installed Apple speech assets; transcription never starts a download.
@available(iOS 26.0, *)
enum AppleSpeechAnalyzerService {
    enum AssetError: LocalizedError {
        case unsupported, notInstalled, empty

        var errorDescription: String? {
            switch self {
            case .unsupported: String(localized: "Apple SpeechAnalyzer does not support this language on this device.")
            case .notInstalled: String(localized: "Download this language in Settings → Speech-to-Text first.")
            case .empty: String(localized: "No speech was detected in this recording.")
            }
        }
    }

    private static func module(_ language: SpeechLanguage) async throws -> SpeechTranscriber {
        guard SpeechTranscriber.isAvailable,
              let locale = await SpeechTranscriber.supportedLocale(equivalentTo: language.preferredNativeLocale)
        else { throw AssetError.unsupported }
        return SpeechTranscriber(locale: locale, preset: .transcription)
    }

    static func install(_ language: SpeechLanguage) async throws {
        let transcriber = try await module(language)
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try await request.downloadAndInstall()
        }
        guard await AssetInventory.status(forModules: [transcriber]) == .installed else { throw AssetError.notInstalled }
    }

    static func isInstalled(_ language: SpeechLanguage) async -> Bool {
        guard let transcriber = try? await module(language) else { return false }
        return await AssetInventory.status(forModules: [transcriber]) == .installed
    }

    static func transcribe(_ url: URL, language: SpeechLanguage) async throws -> String {
        let transcriber = try await module(language)
        guard await AssetInventory.status(forModules: [transcriber]) == .installed else {
            throw AssetError.notInstalled
        }
        let file = try AVAudioFile(forReading: url)
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        return try await withTaskCancellationHandler {
            async let collected = collect(transcriber)
            do {
                if let end = try await analyzer.analyzeSequence(from: file) {
                    try await analyzer.finalizeAndFinish(through: end)
                } else {
                    await analyzer.cancelAndFinishNow()
                }
                let text = try await collected.trimmingCharacters(in: .whitespacesAndNewlines)
                try Task.checkCancellation()
                guard !text.isEmpty else { throw AssetError.empty }
                return text
            } catch {
                await analyzer.cancelAndFinishNow()
                throw error
            }
        } onCancel: {
            Task { await analyzer.cancelAndFinishNow() }
        }
    }

    private static func collect(_ transcriber: SpeechTranscriber) async throws -> String {
        var text = ""
        for try await result in transcriber.results where result.isFinal {
            text += String(result.text.characters)
        }
        return text
    }
}
