import Foundation
import SwiftUI

/// Persists the Coach conversation locally in UserDefaults so the history survives app restarts,
/// and exposes a reset to let the user start fresh whenever they want.
@Observable
class ChatStore {
    private(set) var messages: [ChatMessage] = []
    @ObservationIgnored var onReset: (() -> Void)?
    private(set) var resetRevision = 0

    private let defaults: UserDefaults
    private let storageKey = "coachChatHistory"
    /// We always persist the full history so the user keeps their conversation,
    /// but we cap what we send to the LLM to control token cost.
    static let maxMessagesInContext = 20

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
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
        messages[idx] = ChatMessage(
            id: old.id,
            role: .assistant,
            content: content,
            timestamp: old.timestamp,
            attachmentImageData: old.attachmentImageData,
            exerciseProposals: old.exerciseProposals
        )
        save()
    }

    func reloadFromDefaults() {
        load()
    }

    func reset() {
        resetRevision &+= 1
        onReset?()
        messages = []
        save()
    }

    /// Trailing slice of messages to send as conversation history to the LLM. The system prompt
    /// is built separately each turn so the context stays fresh as the user logs more food/weights.
    func contextMessages() -> [ChatMessage] {
        Array(messages.suffix(Self.maxMessagesInContext))
    }

    // MARK: - Coach proposals

    func exerciseProposal(_ proposalID: String, in messageID: UUID) -> CoachExerciseProposal? {
        messages.first { $0.id == messageID }?.exerciseProposals?.first { $0.id == proposalID }
    }

    /// The user tapped Add. `apply` runs only while the proposal is still pending, and the card
    /// is marked added only after it succeeds: a thrown error leaves the card pending for a
    /// retry, and a second tap never writes twice.
    func addExerciseProposal(
        _ proposalID: String,
        in messageID: UUID,
        apply: (CoachExerciseProposal) throws -> Void
    ) rethrows {
        guard let proposal = exerciseProposal(proposalID, in: messageID), proposal.status == .pending else { return }
        try apply(proposal)
        setStatus(.added, of: proposalID, in: messageID)
    }

    /// The user tapped Dismiss. Nothing is written, and a resolved card keeps its outcome.
    func dismissExerciseProposal(_ proposalID: String, in messageID: UUID) {
        guard exerciseProposal(proposalID, in: messageID)?.status == .pending else { return }
        setStatus(.dismissed, of: proposalID, in: messageID)
    }

    private func setStatus(_ status: CoachExerciseProposal.Status, of proposalID: String, in messageID: UUID) {
        guard let messageIndex = messages.firstIndex(where: { $0.id == messageID }),
              let proposalIndex = messages[messageIndex].exerciseProposals?.firstIndex(where: { $0.id == proposalID })
        else { return }
        messages[messageIndex].exerciseProposals?[proposalIndex].status = status
        save()
    }

    // MARK: - Persistence

    private func save() {
        if let data = try? JSONEncoder().encode(messages) {
            defaults.set(data, forKey: storageKey)
        }
    }

    private func load() {
        guard let data = defaults.data(forKey: storageKey),
              let decoded = try? JSONDecoder().decode([ChatMessage].self, from: data)
        else { return }
        messages = decoded
    }
}
