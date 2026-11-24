import SwiftUI

@available(iOS 26.0, *)
struct AppleSpeechAnalyzerAssetRow: View {
    let language: SpeechLanguage
    @State private var installedLanguage: SpeechLanguage?
    @State private var requestedLanguage: SpeechLanguage?
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if installedLanguage == language {
                Label("Apple speech language is ready", systemImage: "checkmark.circle")
            } else {
                Button {
                    let requested = language
                    requestedLanguage = requested
                    message = nil
                    Task {
                        defer { requestedLanguage = nil }
                        do {
                            try await AppleSpeechAnalyzerService.install(requested)
                            installedLanguage = requested
                        } catch {
                            if requested == language { message = error.localizedDescription }
                        }
                    }
                } label: {
                    Label("Download Apple speech language", systemImage: "arrow.down.circle")
                }
                .buttonStyle(.borderless)
                .disabled(requestedLanguage != nil)
            }
            if requestedLanguage == language {
                ProgressView("Downloading Apple speech language…")
            }
            if let message {
                Text(message).font(.caption).foregroundStyle(.secondary)
            }
        }
        .task(id: language) {
            message = nil
            let current = language
            if await AppleSpeechAnalyzerService.isInstalled(current), !Task.isCancelled {
                installedLanguage = current
            }
        }
    }
}
