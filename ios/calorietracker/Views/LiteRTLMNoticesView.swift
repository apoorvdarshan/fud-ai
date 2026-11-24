import SwiftUI
import UIKit

struct LiteRTLMNoticesView: View {
    var body: some View {
        BundledNoticesView(
            url: Self.noticesURL(in: .main),
            title: LocalModelStrings.text("notices.title", defaultValue: "LiteRT-LM Notices"),
            accessibilityLabel: LocalModelStrings.text(
                "notices.accessibilityLabel",
                defaultValue: "LiteRT-LM third-party notices"
            )
        )
    }

    static func noticesURL(in bundle: Bundle) -> URL? {
        bundle.url(
            forResource: "THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0",
            withExtension: "txt"
        )
    }
}

struct WhisperBaseNoticesView: View {
    var body: some View {
        BundledNoticesView(
            url: Self.noticesURL(in: .main),
            title: "Whisper Base · MIT",
            accessibilityLabel: "Whisper Base · MIT"
        )
    }

    static func noticesURL(in bundle: Bundle) -> URL? {
        bundle.url(
            forResource: "THIRD_PARTY_NOTICES_WhisperBase",
            withExtension: "txt"
        )
    }
}

struct SherpaONNXNoticesView: View {
    static var title: String { String(localized: "Sherpa-ONNX and speech model notices") }

    var body: some View {
        BundledNoticesView(
            url: Bundle.main.url(forResource: "THIRD_PARTY_NOTICES_SherpaONNX", withExtension: "txt"),
            title: Self.title,
            accessibilityLabel: Self.title
        )
    }
}

struct OfflineVoiceNoticesView: View {
    static var title: String { String(localized: "Offline Voice notices", table: "OfflineVoice") }

    var body: some View {
        BundledNoticesView(
            url: Bundle.main.url(forResource: "THIRD_PARTY_NOTICES_OfflineVoice", withExtension: "txt"),
            title: Self.title,
            accessibilityLabel: Self.title
        )
    }
}

private struct BundledNoticesView: View {
    private enum LoadState {
        case loading
        case loaded(String)
        case failed(String)
    }

    let url: URL?
    let title: String
    let accessibilityLabel: String

    @State private var loadState: LoadState = .loading

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView(localized("notices.loading", "Loading notices…"))
            case .loaded(let text):
                SelectableNoticeTextView(text: text)
                    .accessibilityLabel(accessibilityLabel)
            case .failed(let message):
                ContentUnavailableView(
                    localized("notices.unavailable", "Notices Unavailable"),
                    systemImage: "doc.text.magnifyingglass",
                    description: Text(message)
                )
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadNotices() }
    }

    private func loadNotices() async {
        guard case .loading = loadState else { return }
        guard let url else {
            loadState = .failed(localized(
                "notices.fileMissing",
                "The bundled notice file could not be found."
            ))
            return
        }

        do {
            let text = try await Task.detached(priority: .utility) {
                try String(contentsOf: url, encoding: .utf8)
            }.value
            loadState = .loaded(text)
        } catch {
            loadState = .failed(localized(
                "notices.fileOpenFailed",
                "The bundled notice file could not be opened."
            ))
        }
    }

    private func localized(_ key: String, _ defaultValue: String) -> String {
        LocalModelStrings.text(key, defaultValue: defaultValue)
    }
}

private struct SelectableNoticeTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView(usingTextLayoutManager: true)
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.backgroundColor = .clear
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.textColor = .label
        textView.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 24, right: 12)
        textView.accessibilityTraits = .staticText
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        guard textView.text != text else { return }
        textView.text = text
        textView.setContentOffset(.zero, animated: false)
    }
}
