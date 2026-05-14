import Foundation
import SwiftUI

/// Persists the Coach conversation locally in UserDefaults so the history survives app restarts,
/// and exposes a reset to let the user start fresh whenever they want.
@Observable
class ChatStore {
    private(set) var messages: [ChatMessage] = []

    private let storageKey = "coachChatHistory"
    /// We always persist the full history so the user keeps their conversation,
    /// but we cap what we send to the LLM to control token cost.
    static let maxMessagesInContext = 20

    init() {
        load()
    }

    func append(_ message: ChatMessage) {
        messages.append(message)
        save()
    }

    /// Replace the last assistant message. Useful for streaming responses or error-fix retries.
    func replaceLastAssistant(with content: String) {
        guard let idx = messages.lastIndex(where: { $0.role == .assistant }) else { return }
        let old = messages[idx]
        messages[idx] = ChatMessage(id: old.id, role: .assistant, content: content, timestamp: old.timestamp, attachmentImageData: old.attachmentImageData)
        save()
    }

    func reset() {
        messages = []
        save()
    }

    /// Trailing slice of messages to send as conversation history to the LLM. The system prompt
    /// is built separately each turn so the context stays fresh as the user logs more food/weights.
    func contextMessages() -> [ChatMessage] {
        Array(messages.suffix(Self.maxMessagesInContext))
    }

    // MARK: - Persistence

    private func save() {
        if let data = try? JSONEncoder().encode(messages) {
            UserDefaults.standard.set(data, forKey: storageKey)
        }
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([ChatMessage].self, from: data)
        else { return }
        messages = decoded
    }
}
