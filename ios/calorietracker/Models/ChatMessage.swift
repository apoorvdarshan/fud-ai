import Foundation

struct ChatMessage: Identifiable, Codable, Equatable {
    enum Role: String, Codable {
        case user
        case assistant
    }

    let id: UUID
    let role: Role
    let content: String
    let timestamp: Date
    let attachmentImageData: Data?

    init(
        id: UUID = UUID(),
        role: Role,
        content: String,
        timestamp: Date = .now,
        attachmentImageData: Data? = nil
    ) {
        self.id = id
        self.role = role
        self.content = content
        self.timestamp = timestamp
        self.attachmentImageData = attachmentImageData
    }
}
