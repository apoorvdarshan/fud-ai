import SwiftUI

/// Mounted at the app root rather than on the leaderboard. Only opted-in
/// periods are calculated and synced, and no leaderboard reads happen here.
struct ChallengePeriodScoreSync: ViewModifier {
    let foodStore: FoodStore
    let waterStore: WaterStore
    let profileStore: ProfileStore
    let workoutStore: StrengthWorkoutStore
    let challengeStore: WeeklyChallengeStore
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(WaterSettings.enabledKey) private var waterEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterGoal = WaterSettings.defaultDailyGoalMl
    @State private var now = Date.now

    private var scores: [ChallengePeriodScore] {
        guard scenePhase == .active, challengeStore.isJoined, !challengeStore.enabledPeriods.isEmpty else { return [] }
        return ChallengePeriodScore.calculateEnabled(
            challengeStore.enabledPeriods, now: now,
            foods: foodStore.entries.map { .init(date: $0.timestamp, calories: $0.calories) },
            water: waterStore.entries.map { .init(date: $0.date, milliliters: $0.milliliters) },
            activities: workoutStore.completedSessions.map { .init(date: $0.calendarDiaryDate, calories: $0.caloriesBurned) },
            calorieGoal: profileStore.profile.effectiveCalories, waterGoal: waterEnabled ? waterGoal : nil
        )
    }

    func body(content: Content) -> some View {
        let scores = scores
        content
            .onChange(of: scenePhase, initial: true) { _, phase in
                if phase == .active { now = .now }
            }
            .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in now = .now }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in now = .now }
            .task(id: scores) {
                guard !scores.isEmpty else { return }
                while !Task.isCancelled {
                    await challengeStore.syncEnabledPeriodScores(scores)
                    do { try await Task.sleep(for: .seconds(60)) }
                    catch { return }
                }
            }
    }
}
