import Foundation

@Observable
final class FastingStore {
    private(set) var sessions: [FastingSession] = []
    var onSessionsChanged: (() -> Void)?
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        guard let data = defaults.data(forKey: FastingSettings.sessionsKey),
              let decoded = try? JSONDecoder().decode([FastingSession].self, from: data) else { return }
        sessions = decoded.sorted { $0.startedAt < $1.startedAt }
    }

    static func persistedActiveSession(defaults: UserDefaults = .standard) -> FastingSession? {
        guard let data = defaults.data(forKey: FastingSettings.sessionsKey),
              let decoded = try? JSONDecoder().decode([FastingSession].self, from: data) else {
            return nil
        }
        return decoded.last(where: \.isActive)
    }

    var activeSession: FastingSession? {
        sessions.last(where: \.isActive)
    }

    @discardableResult
    func start(goalMinutes: Int, at date: Date = .now) -> FastingSession? {
        guard activeSession == nil else { return nil }
        let session = FastingSession(startedAt: date, goalMinutes: goalMinutes)
        guard !overlapsExistingSession(session) else { return nil }
        sessions.append(session)
        save()
        return session
    }

    @discardableResult
    func endActive(at date: Date = .now) -> FastingSession? {
        guard let active = activeSession,
              let index = sessions.firstIndex(where: { $0.id == active.id }) else { return nil }
        sessions[index].endedAt = max(date, active.startedAt)
        let completed = sessions[index]
        save()
        return completed
    }

    func cancelActive() {
        guard let active = activeSession else { return }
        sessions.removeAll { $0.id == active.id }
        save()
    }

    @discardableResult
    func update(_ session: FastingSession) -> Bool {
        guard let index = sessions.firstIndex(where: { $0.id == session.id }) else { return false }
        var validated = session
        validated.goalMinutes = min(
            max(validated.goalMinutes, FastingSettings.minimumGoalMinutes),
            FastingSettings.maximumGoalMinutes
        )
        if let endedAt = validated.endedAt, endedAt < validated.startedAt {
            validated.endedAt = validated.startedAt
        }
        if validated.isActive,
           sessions.contains(where: { $0.id != validated.id && $0.isActive }) {
            return false
        }
        guard !overlapsExistingSession(validated, excluding: validated.id) else { return false }
        sessions[index] = validated
        sessions.sort { $0.startedAt < $1.startedAt }
        save()
        return true
    }

    func delete(id: UUID) {
        sessions.removeAll { $0.id == id }
        save()
    }

    func completed(on day: Date) -> [FastingSession] {
        sessions.filter { $0.occurs(on: day) }.sorted { ($0.endedAt ?? .distantPast) > ($1.endedAt ?? .distantPast) }
    }

    func reloadFromDefaults() {
        guard let data = defaults.data(forKey: FastingSettings.sessionsKey),
              let decoded = try? JSONDecoder().decode([FastingSession].self, from: data) else {
            sessions = []
            onSessionsChanged?()
            return
        }
        sessions = decoded.sorted { $0.startedAt < $1.startedAt }
        onSessionsChanged?()
    }

    func clear() {
        sessions = []
        defaults.removeObject(forKey: FastingSettings.sessionsKey)
        onSessionsChanged?()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(sessions) else { return }
        defaults.set(data, forKey: FastingSettings.sessionsKey)
        onSessionsChanged?()
    }

    private func overlapsExistingSession(_ candidate: FastingSession, excluding id: UUID? = nil) -> Bool {
        let candidateEnd = candidate.endedAt ?? .distantFuture
        return sessions.contains { existing in
            guard existing.id != id else { return false }
            let existingEnd = existing.endedAt ?? .distantFuture
            return candidate.startedAt < existingEnd && existing.startedAt < candidateEnd
        }
    }
}
