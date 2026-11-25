import SwiftUI

struct WeeklyChallengeView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @Environment(WeeklyChallengeStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterDailyGoal = WaterSettings.defaultDailyGoalMl

    @State private var category: WeeklyChallengeCategory = .overall
    @State private var period: ChallengePeriod = .weekly
    @State private var now = Date.now
    @State private var rankingPage = 0
    @State private var failedRankingPage: RankingPageRequest?
    @State private var profileSheetMode: WeeklyChallengeProfileSheetMode?
    @State private var reportTarget: WeeklyChallengeParticipant?
    @State private var showBlockedParticipants = false
    @State private var showLeaveConfirmation = false
    @State private var showReportConfirmation = false
    @State private var showLeavePeriod = false

    private struct RefreshIdentity: Hashable {
        let category: WeeklyChallengeCategory
        let score: WeeklyChallengeScore
        let period: ChallengePeriod
        let periodScore: ChallengePeriodScore?
        let isJoined: Bool
        let hasPendingDeletion: Bool
    }

    private struct RankingPageRequest: Hashable {
        let category: WeeklyChallengeCategory
        let weekStart: String
        let offset: Int
        let period: ChallengePeriod
    }

    private var localScore: WeeklyChallengeScore {
        WeeklyChallengeAggregator.score(
            now: now,
            foods: foodStore.entries.map {
                WeeklyChallengeFoodSample(date: $0.timestamp, calories: $0.calories)
            },
            water: waterStore.entries.map {
                WeeklyChallengeWaterSample(date: $0.date, milliliters: $0.milliliters)
            },
            activities: strengthWorkoutStore.completedSessions.map {
                WeeklyChallengeActivitySample(
                    date: $0.calendarDiaryDate,
                    calories: $0.caloriesBurned
                )
            },
            calorieGoal: profileStore.profile.effectiveCalories,
            hydrationEnabled: waterTrackingEnabled,
            hydrationGoalMilliliters: waterDailyGoal
        )
    }

    private var currentWeek: WeeklyChallengeWeek {
        WeeklyChallengeWeek.containing(now)
    }

    private var periodScore: ChallengePeriodScore? {
        guard period != .weekly else { return nil }
        return ChallengePeriodScore.calculate(
            period: period, now: now,
            foods: foodStore.entries.map { .init(date: $0.timestamp, calories: $0.calories) },
            water: waterStore.entries.map { .init(date: $0.date, milliliters: $0.milliliters) },
            activities: strengthWorkoutStore.completedSessions.map { .init(date: $0.calendarDiaryDate, calories: $0.caloriesBurned) },
            calorieGoal: profileStore.profile.effectiveCalories,
            waterGoal: waterTrackingEnabled ? waterDailyGoal : nil
        )
    }

    private var currentStartKey: String { periodScore?.periodStart ?? localScore.weekStart }

    private var displayedLeaderboard: WeeklyChallengeLeaderboardResponse? {
        store.leaderboardFor(category: category, weekStart: currentStartKey, period: period)
    }

    private var displayedViewer: WeeklyChallengeParticipant? {
        rankingPageResponse?.viewer ?? displayedLeaderboard?.viewer
    }

    private var rankingPageRequest: RankingPageRequest? {
        guard rankingPage > 0, displayedLeaderboard?.totalCount != nil else { return nil }
        return RankingPageRequest(
            category: category, weekStart: currentStartKey,
            offset: rankingPage * WeeklyChallengeLeaderboardCache.pageSize, period: period
        )
    }

    private var rankingPageResponse: WeeklyChallengeLeaderboardResponse? {
        guard let request = rankingPageRequest else { return displayedLeaderboard }
        return store.leaderboardFor(category: request.category, weekStart: request.weekStart, offset: request.offset, period: request.period)
    }

    private func loadRankingPage(_ request: RankingPageRequest, force: Bool = false) async {
        let success = await store.loadPage(category: request.category, weekStart: request.weekStart, offset: request.offset,
                                           period: request.period, force: force)
        guard !Task.isCancelled, rankingPageRequest == request else { return }
        failedRankingPage = success ? nil : request
        if let count = rankingPageResponse?.totalCount, request.offset >= count {
            rankingPage = max(0, (count - 1) / WeeklyChallengeLeaderboardCache.pageSize)
        }
    }

    private func refreshSelected(force: Bool = false) async {
        if let score = periodScore {
            await store.refreshPeriod(category: category, score: score, force: force)
        } else {
            await store.refresh(category: category, score: localScore)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            periodSelector
            if store.hasPendingDeletion {
                pendingDeletionView
            } else if period != .weekly, !store.isJoined || !store.enabledPeriods.contains(period) {
                periodIntroduction
            } else if store.isJoined {
                joinedView
            } else {
                joinIntroduction
            }
        }
        .background(AppColors.appBackground)
        .task(
            id: RefreshIdentity(
                category: category,
                score: localScore,
                period: period,
                periodScore: periodScore,
                isJoined: store.isJoined,
                hasPendingDeletion: store.hasPendingDeletion
            )
        ) {
            if store.hasPendingDeletion {
                await store.retryPendingDeletionIfNeeded()
            } else if store.isJoined {
                await refreshSelected()
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(60))
                    guard !Task.isCancelled, store.isJoined else { return }
                    guard scenePhase == .active else { continue }
                    now = .now
                    await refreshSelected()
                }
            }
        }
        .onChange(of: category) { _, _ in
            rankingPage = 0
        }
        .onChange(of: currentStartKey) { _, _ in
            rankingPage = 0
        }
        .onChange(of: period) { _, _ in rankingPage = 0 }
        .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in now = .now }
        .task(id: rankingPageRequest) {
            failedRankingPage = nil
            if let request = rankingPageRequest { await loadRankingPage(request) }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            now = .now
            Task {
                if store.hasPendingDeletion {
                    await store.retryPendingDeletionIfNeeded()
                } else if store.isJoined {
                    await refreshSelected()
                }
            }
        }
        .flashSheet(item: $profileSheetMode) { mode in
            WeeklyChallengeProfileSheet(
                mode: mode,
                profile: store.publicProfile,
                score: localScore,
                isSaving: store.isProfileMutationInProgress,
                serverErrorMessage: store.errorMessage
            ) { input, acceptedRules, eligibilityAccepted in
                switch mode {
                case .join:
                    return await store.join(
                        input: input,
                        acceptedRules: acceptedRules,
                        eligibilityAccepted: eligibilityAccepted,
                        score: localScore,
                        category: category
                    )
                case .edit:
                    let updated = await store.updateProfile(input)
                    if updated {
                        await store.refresh(category: category, score: localScore)
                    }
                    return updated
                }
            }
        }
        .flashSheet(item: $reportTarget) { participant in
            WeeklyChallengeReportView(
                participant: participant,
                isSubmitting: store.isSubmittingReport,
                serverErrorMessage: store.errorMessage
            ) { reason, details in
                let reported = await store.report(
                    participant: participant,
                    reason: reason,
                    details: details
                )
                if reported { showReportConfirmation = true }
                return reported
            }
        }
        .flashSheet(isPresented: $showBlockedParticipants) {
            WeeklyChallengeBlockedParticipantsView(
                store: store,
                knownParticipants: store.knownParticipants
            )
        }
        .alert(
            Text("Leave this challenge?", tableName: "ChallengeHub"),
            isPresented: $showLeavePeriod
        ) {
            Button("Cancel", role: .cancel) {}
            Button(role: .destructive) { Task { await store.leavePeriod(period) } } label: {
                Text("Leave and remove scores", tableName: "ChallengeHub")
            }
        } message: {
            Text("This removes your scores for this time period only. Your other challenges and diary stay unchanged.", tableName: "ChallengeHub")
        }
        .alert(
            Text("Leave all challenges?", tableName: "ChallengeHub"),
            isPresented: $showLeaveConfirmation
        ) {
            Button(WeeklyChallengeL10n.text("Cancel"), role: .cancel) { }
            Button(
                WeeklyChallengeL10n.text("Leave & Delete Remote Data"),
                role: .destructive
            ) {
                Task { await store.leaveAndDeleteRemoteData() }
            }
        } message: {
            Text("Your public profile, all scores, and challenge reports will be deleted. Your private diary stays on this device.", tableName: "ChallengeHub")
        }
        .alert(
            WeeklyChallengeL10n.text("Report Sent"),
            isPresented: $showReportConfirmation
        ) {
            Button(WeeklyChallengeL10n.text("OK"), role: .cancel) { }
        } message: {
            Text(WeeklyChallengeL10n.text("Thank you. The report was submitted for review."))
        }
        .alert(
            WeeklyChallengeL10n.text("Weekly Challenge"),
            isPresented: Binding(
                get: {
                    store.errorMessage != nil
                        && profileSheetMode == nil
                        && reportTarget == nil
                },
                set: { if !$0 { store.clearError() } }
            )
        ) {
            Button(WeeklyChallengeL10n.text("OK"), role: .cancel) {
                store.clearError()
            }
        } message: {
            Text(store.errorMessage ?? "")
        }
    }

    private var periodSelector: some View {
        let selector = Picker(selection: $period) {
            ForEach(ChallengePeriod.allCases) { candidate in
                Text(String(localized: candidate.name, table: "ChallengeHub")).tag(candidate)
            }
        } label: {
            Text("Challenges", tableName: "ChallengeHub")
        }
        return Group {
            if typeSize.isAccessibilitySize { selector.pickerStyle(.menu) }
            else { selector.pickerStyle(.segmented) }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .accessibilityIdentifier("challenges.period")
    }

    private var periodIntroduction: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                challengeHeader
                VStack(alignment: .leading, spacing: 12) {
                    Text("Build habits, not bigger numbers", tableName: "ChallengeHub")
                        .font(.system(.title2, design: .rounded, weight: .bold))
                    periodRules
                    if !store.isJoined {
                        Text("Join Weekly to create your challenge profile first.", tableName: "ChallengeHub")
                            .font(.subheadline)
                        Button { period = .weekly } label: { Text("Go to Weekly", tableName: "ChallengeHub") }
                            .buttonStyle(.borderedProminent)
                    } else if !store.supportsExpandedPeriods {
                        Text("This board needs an updated challenge service.", tableName: "ChallengeHub")
                            .font(.subheadline).foregroundStyle(.secondary)
                    } else if let score = periodScore {
                        Text("Joining shares your name and habit totals for this period with other participants. No diary entries, weight, or calorie amounts are shared.", tableName: "ChallengeHub")
                            .font(.subheadline)
                        Button {
                            Task { await store.refreshPeriod(category: category, score: score, accept: true, force: true) }
                        } label: { Text("Join and share scores", tableName: "ChallengeHub") }
                        .buttonStyle(.borderedProminent)
                        .disabled(store.busyPeriod != nil)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(18)
                .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 22))
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
        }
    }

    private var periodRules: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label {
                Text("Earn one point per habit each day: movement, food logging, nutrition goal, and water goal.", tableName: "ChallengeHub")
            } icon: { Image(systemName: "checkmark.circle") }
            Label {
                Text("Earn a bonus point on days with three or more habits.", tableName: "ChallengeHub")
            } icon: { Image(systemName: "sparkles") }
            if period == .monthly {
                Label {
                    Text("Your longest run of days with two or more habits earns one point for each day after the first.", tableName: "ChallengeHub")
                } icon: { Image(systemName: "flame") }
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }

    private var joinIntroduction: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "trophy.fill")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityHidden(true)

                Text("Challenges", tableName: "ChallengeHub")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                Text(
                    WeeklyChallengeL10n.text(
                        "Join an optional, privacy-first leaderboard based on healthy weekly habits—not body weight. You must be 18 or older."
                    )
                )
                .font(.system(.body, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

                disclosureCard(score: localScore)
                WeeklyChallengePointsExplanationView()

                Button {
                    store.clearError()
                    profileSheetMode = .join
                } label: {
                    Text(WeeklyChallengeL10n.text("Join Weekly Challenge"))
                        .font(.system(.headline, design: .rounded))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                }
                .buttonStyle(FlashActionButtonStyle())
                .tint(AppColors.calorie)
                .accessibilityHint(
                    WeeklyChallengeL10n.text("Opens the optional public profile and consent form.")
                )

                Text(
                    WeeklyChallengeL10n.text(
                        "Leaderboard results are visible only to people who join. You can leave and delete your remote challenge data at any time."
                    )
                )
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            }
            .padding(20)
        }
        .refreshable { }
    }

    private var pendingDeletionView: some View {
        ContentUnavailableView {
            Label(
                WeeklyChallengeL10n.text("Challenge Deletion Pending"),
                systemImage: "icloud.and.arrow.up"
            )
        } description: {
            Text(
                WeeklyChallengeL10n.text(
                    "Fud AI kept only the secure deletion credential and will retry removing your remote challenge data when you are online."
                )
            )
        } actions: {
            Button(WeeklyChallengeL10n.text("Retry Deletion")) {
                Task { await store.retryPendingDeletionIfNeeded() }
            }
            .buttonStyle(FlashActionButtonStyle())
            .tint(AppColors.calorie)
        }
    }

    private var joinedView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 14) {
                    challengeHeader
                    categorySelector

                    if let score = periodScore {
                        VStack(alignment: .leading, spacing: 10) {
                            Label {
                                Text("Your points", tableName: "ChallengeHub")
                            } icon: { Image(systemName: "star.circle.fill").foregroundStyle(AppColors.accentText) }
                            .font(.headline)
                            Text(score.points, format: .number)
                                .font(.system(.largeTitle, design: .rounded, weight: .black).monospacedDigit())
                            if let rank = displayedViewer?.rank { Text("#\(rank)").font(.subheadline.weight(.semibold)) }
                            periodRules
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 22))
                    }

                    if let response = displayedLeaderboard {
                        leaderboardRows(response)
                            .id("challengeRankings")
                    } else if store.isRefreshing || store.busyPeriod == period {
                    ProgressView()
                        .padding(.vertical, 32)
                        .accessibilityLabel(WeeklyChallengeL10n.text("Loading leaderboard"))
                } else {
                    Text(WeeklyChallengeL10n.text("No leaderboard results are available yet."))
                        .font(.system(.body, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 28)
                }

                if period == .weekly, let viewer = displayedViewer {
                    WeeklyChallengeViewerCard(
                        participant: viewer,
                        category: category,
                        onEditProfile: {
                            store.clearError()
                            profileSheetMode = .edit
                        },
                        onLeave: { showLeaveConfirmation = true }
                    )
                }

                if period == .weekly {
                    WeeklyChallengePointsExplanationView()
                } else {
                    Button(role: .destructive) { showLeavePeriod = true } label: {
                        Text("Leave and remove scores", tableName: "ChallengeHub")
                    }
                    .disabled(store.busyPeriod != nil)
                }

                if !store.blockedParticipantIDs.isEmpty {
                    Button(WeeklyChallengeL10n.text("Manage Blocked Participants")) {
                        showBlockedParticipants = true
                    }
                    .buttonStyle(.bordered)
                    .padding(.top, 4)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
            }
            .refreshable {
                await refreshSelected(force: true)
                if let request = rankingPageRequest { await loadRankingPage(request, force: true) }
            }
            .onChange(of: rankingPage) { _, _ in
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo("challengeRankings", anchor: .top)
                }
            }
        }
    }

    private var categorySelector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(WeeklyChallengeCategory.allCases) { option in
                    Button {
                        category = option
                    } label: {
                        Label(option.title, systemImage: option.systemImage)
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 9)
                            .foregroundStyle(category == option ? Color.white : Color.primary)
                            .background(
                                category == option ? AppColors.calorie : AppColors.appCard,
                                in: Capsule()
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(category == option ? .isSelected : [])
                }
            }
        }
        .accessibilityLabel(WeeklyChallengeL10n.text("Challenge category"))
    }

    private var challengeHeader: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    Label(weekRangeText, systemImage: "trophy.fill")
                        .font(.system(.title3, design: .rounded, weight: .bold))
                    Text(statusText)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(store.isOffline ? .orange : .secondary)
                }
                Spacer()
                if (period == .weekly && store.isRefreshing) || store.busyPeriod == period {
                    ProgressView()
                        .controlSize(.small)
                        .accessibilityLabel(WeeklyChallengeL10n.text("Updating leaderboard"))
                }
            }
            if period == .weekly { WeekCalendarStrip(weekStart: currentWeek.start) }
        }
        .padding(14)
        .background(AppColors.calorie.opacity(0.10), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private var weekRangeText: String {
        if period == .daily { return now.formatted(.dateTime.weekday(.wide).month(.wide).day()) }
        if period == .monthly { return now.formatted(.dateTime.month(.wide).year()) }
        let start = currentWeek.start.formatted(.dateTime.month(.abbreviated).day())
        let end = currentWeek.end.formatted(.dateTime.month(.abbreviated).day().year())
        return WeeklyChallengeL10n.format("%1$@ – %2$@", start, end)
    }

    private var statusText: String {
        if period != .weekly {
            return displayedLeaderboard?.totalCount.map { String(localized: "Participants: \($0)", table: "ChallengeHub") }
                ?? String(localized: "Challenges", table: "ChallengeHub")
        }
        let categoryUpdated = store.lastUpdatedFor(
            category: category,
            weekStart: localScore.weekStart
        )
        if store.isOffline {
            if let categoryUpdated {
                return WeeklyChallengeL10n.format(
                    "Offline — showing saved results · Last updated %1$@",
                    categoryUpdated.formatted(date: .omitted, time: .shortened)
                )
            }
            return WeeklyChallengeL10n.text("Offline — showing saved results")
        }
        guard let categoryUpdated else {
            return WeeklyChallengeL10n.text("Waiting for first update")
        }
        return WeeklyChallengeL10n.format(
            "Last updated %1$@",
            categoryUpdated.formatted(date: .omitted, time: .shortened)
        )
    }

    private func orderedRankings(_ response: WeeklyChallengeLeaderboardResponse) -> [WeeklyChallengeParticipant] {
        let rows = response.rankings.filter {
            !store.isBlocked(participantID: $0.participantId)
        }
        return rows.sorted { lhs, rhs in
            if lhs.rank != rhs.rank { return lhs.rank < rhs.rank }
            return lhs.participantId < rhs.participantId
        }
    }

    @ViewBuilder
    private func leaderboardRows(_ response: WeeklyChallengeLeaderboardResponse) -> some View {
        let viewerID = displayedViewer?.participantId ?? store.participantID
        let page = rankingPageResponse
        let rows = page.map(orderedRankings) ?? []
        let maxRank = page?.totalCount ?? response.totalCount ?? response.rankings.map(\.rank).max() ?? 0
        let pageSize = WeeklyChallengeLeaderboardCache.pageSize
        let pageCount = max(1, (maxRank + pageSize - 1) / pageSize)
        let currentPage = min(rankingPage, pageCount - 1)
        let startRank = currentPage * pageSize + 1
        let endRank = min(startRank + pageSize - 1, maxRank)

        VStack(alignment: .leading, spacing: 0) {
            Text(WeeklyChallengeL10n.text("Rankings"))
                .font(.system(.title3, design: .rounded, weight: .bold))
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 6)

            if let request = rankingPageRequest, failedRankingPage == request, page == nil {
                Button(WeeklyChallengeL10n.text("Retry")) {
                    failedRankingPage = nil
                    Task { await loadRankingPage(request, force: true) }
                }
                .padding(16)
            } else if page == nil {
                ProgressView().padding(16).accessibilityLabel(WeeklyChallengeL10n.text("Loading leaderboard"))
            } else if rows.isEmpty {
                Text(WeeklyChallengeL10n.text("No other participants are ranked yet."))
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
                    .padding(16)
            } else {
                let pageRows = rows.filter { $0.rank >= startRank && $0.rank <= endRank }
                VStack(spacing: 8) {
                    ForEach(pageRows) { participant in
                        WeeklyChallengeParticipantRow(
                            participant: participant,
                            category: category,
                            isViewer: participant.isViewer || participant.participantId == viewerID,
                            onReport: {
                                store.clearError()
                                reportTarget = participant
                            },
                            onBlock: { store.block(participant) }
                        )
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
            if page != nil, let request = rankingPageRequest, failedRankingPage == request {
                Button(WeeklyChallengeL10n.text("Retry")) {
                    failedRankingPage = nil
                    Task { await loadRankingPage(request, force: true) }
                }
                .padding(12)
            }
            if pageCount > 1 {
                    VStack(spacing: 10) {
                        Text("#\(startRank)–#\(endRank)")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                        HStack(spacing: 10) {
                            rankingPageButton(
                                title: WeeklyChallengeL10n.text("Previous"),
                                systemImage: "chevron.left",
                                imageFirst: true,
                                enabled: currentPage > 0
                            ) {
                                rankingPage = max(currentPage - 1, 0)
                            }
                            rankingPageButton(
                                title: WeeklyChallengeL10n.text("Next"),
                                systemImage: "chevron.right",
                                imageFirst: false,
                                enabled: currentPage < pageCount - 1
                            ) {
                                rankingPage = min(currentPage + 1, pageCount - 1)
                            }
                        }
                        if let rank = displayedViewer?.rank, rank <= maxRank,
                           (rank - 1) / pageSize != currentPage {
                            Button {
                                rankingPage = (rank - 1) / pageSize
                            } label: {
                                Text("Jump to my rank", tableName: "ChallengeHub")
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                    .padding(.bottom, 14)
            }
        }
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func rankingPageButton(
        title: String,
        systemImage: String,
        imageFirst: Bool,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if imageFirst {
                    Image(systemName: systemImage)
                }
                Text(title)
                if !imageFirst {
                    Image(systemName: systemImage)
                }
            }
            .font(.system(.body, design: .rounded, weight: .semibold))
            .frame(maxWidth: .infinity)
            .frame(minHeight: 48)
            .foregroundStyle(enabled ? pageButtonForeground : Color.secondary)
            .background(
                enabled ? AppColors.calorie : Color.primary.opacity(0.08),
                in: Capsule()
            )
        }
        .buttonStyle(QuietPressButtonStyle())
        .disabled(!enabled)
    }

    private struct QuietPressButtonStyle: ButtonStyle {
        func makeBody(configuration: Configuration) -> some View {
            configuration.label
                .opacity(configuration.isPressed ? 0.85 : 1)
        }
    }

    private var pageButtonForeground: Color {
        switch AppThemeColor.current {
        case .yellow, .lime, .skyCyan, .babyPink, .lavender, .mint:
            Color.black.opacity(0.88)
        default:
            Color.white
        }
    }

    private func disclosureCard(score: WeeklyChallengeScore) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(
                WeeklyChallengeL10n.text("Only weekly totals leave your device"),
                systemImage: "lock.shield.fill"
            )
            .font(.system(.headline, design: .rounded, weight: .semibold))

            Text(
                WeeklyChallengeL10n.text(
                    "Fud AI calculates these totals on this device. It never uploads food names, meals, timestamps, water entries, workout details, Health records, body weight, or weight loss."
                )
            )
            .font(.system(.footnote, design: .rounded))
            .foregroundStyle(.secondary)

            Divider()

            WeeklyChallengePayloadView(score: score)
        }
        .padding(16)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

private enum WeeklyChallengeProfileSheetMode: String, Identifiable {
    case join
    case edit

    var id: Self { self }
}

private struct WeeklyChallengePayloadView: View {
    let score: WeeklyChallengeScore

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(WeeklyChallengeL10n.text("Exact weekly aggregate payload"))
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            payloadLine("weekStart", score.weekStart)
            payloadLine("overallPoints", score.overallPoints.formatted())
            payloadLine("activityDays", score.activityDays.formatted())
            payloadLine("nutritionDays", score.nutritionDays.formatted())
            payloadLine("consistencyDays", score.consistencyDays.formatted())
            payloadLine("hydrationDays", score.hydrationDays.formatted())
            payloadLine("activityKcal", score.activityKcal.formatted())
        }
        .accessibilityElement(children: .combine)
    }

    private func payloadLine(_ key: String, _ value: String) -> some View {
        Text("\(key): \(value)")
            .font(.system(.caption, design: .monospaced))
    }
}

private struct WeeklyChallengePointsExplanationView: View {
    @State private var isExpanded = false

    var body: some View {
        DisclosureGroup(isExpanded: $isExpanded) {
            Text(WeeklyChallengeL10n.text("Activity: a day with a positive logged workout burn. Nutrition: daily calories within 85–115% of your goal. Consistency: any food logged that day. Hydration: water goal met when tracking is enabled. Overall: the four day counts added together, up to 28."))
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)
            Text("The selected score comes first. Ties favor more different habits, steadier results across habits, and the other day counts. If those match, the person who reached them first ranks higher. Workout calories do not break ties.", tableName: "ChallengeHub")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
        } label: {
            Label(
                WeeklyChallengeL10n.text("How points work"),
                systemImage: "info.circle"
            )
            .font(.system(.headline, design: .rounded, weight: .semibold))
        }
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct WeeklyChallengeViewerCard: View {
    let participant: WeeklyChallengeParticipant
    let category: WeeklyChallengeCategory
    let onEditProfile: () -> Void
    let onLeave: () -> Void
    @AppStorage(EnergyUnit.storageKey) private var energyUnit = EnergyUnit.defaultUnit

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 14) {
                WeeklyChallengeRankBadge(place: participant.rank, diameter: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(WeeklyChallengeL10n.text("My Position"))
                        .font(.system(.subheadline, design: .rounded, weight: .bold))
                        .foregroundStyle(AppColors.calorie)
                    WeeklyChallengeParticipantIdentity(participant: participant)
                }
            }
            WeeklyChallengeSocialLink(participant: participant)
            Text(WeeklyChallengeParticipantScore.text(for: participant, category: category, energyUnit: energyUnit))
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            WeeklyChallengeWeekTrack(
                label: WeeklyChallengeL10n.text("Overall"),
                value: WeeklyChallengeL10n.format(
                    "%1$@ / 28 pts",
                    participant.overallPoints.formatted()
                ),
                fraction: Double(participant.overallPoints) / 28,
                emphasized: category == .overall
            )
            WeeklyChallengeDayTrack(
                label: WeeklyChallengeL10n.text("Activity"),
                days: participant.activityDays,
                emphasized: category == .activity,
                value: WeeklyChallengeL10n.activityDays(
                    participant.activityDays,
                    burnKcal: participant.activityKcal,
                    energyUnit: energyUnit
                )
            )
            WeeklyChallengeDayTrack(
                label: WeeklyChallengeL10n.text("Nutrition"),
                days: participant.nutritionDays,
                emphasized: category == .nutrition
            )
            WeeklyChallengeDayTrack(
                label: WeeklyChallengeL10n.text("Consistency"),
                days: participant.consistencyDays,
                emphasized: category == .consistency
            )
            WeeklyChallengeDayTrack(
                label: WeeklyChallengeL10n.text("Hydration"),
                days: participant.hydrationDays,
                emphasized: category == .hydration
            )

            HStack(spacing: 8) {
                Button(WeeklyChallengeL10n.text("Edit Public Profile"), action: onEditProfile)
                    .buttonStyle(FlashActionButtonStyle())
                    .tint(AppColors.calorie)
                    .frame(maxWidth: .infinity)
                Button(
                    WeeklyChallengeL10n.text("Leave Challenge"),
                    role: .destructive,
                    action: onLeave
                )
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            }
            .font(.system(.subheadline, design: .rounded, weight: .semibold))
        }
        .padding(16)
        .background(
            AppColors.calorie.opacity(0.11),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(AppColors.calorie.opacity(0.4), lineWidth: 1)
        }
    }
}

private struct WeeklyChallengeWeekTrack: View {
    let label: String
    let value: String
    let fraction: Double
    var emphasized: Bool = false

    var body: some View {
        let bar = emphasized ? AppColors.calorie : Color.primary.opacity(0.38)
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label)
                    .font(.system(.subheadline, design: .rounded, weight: emphasized ? .bold : .regular))
                    .foregroundStyle(emphasized ? Color.primary : Color.secondary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(value)
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                    .foregroundStyle(bar)
            }
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.12))
                    Capsule()
                        .fill(bar)
                        .frame(width: proxy.size.width * min(max(fraction, 0), 1))
                }
            }
            .frame(height: emphasized ? 10 : 8)
        }
    }
}

private struct WeeklyChallengeDayTrack: View {
    let label: String
    let days: Int
    var emphasized: Bool = false
    var value: String? = nil

    private var filled: Int { min(max(days, 0), 7) }

    var body: some View {
        let bar = emphasized ? AppColors.calorie : Color.primary.opacity(0.38)
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label)
                    .font(.system(.subheadline, design: .rounded, weight: emphasized ? .bold : .regular))
                    .foregroundStyle(emphasized ? Color.primary : Color.secondary)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(value ?? WeeklyChallengeL10n.format("%1$@ / 7 days", filled.formatted()))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(emphasized ? bar : Color.primary)
            }
            HStack(spacing: 4) {
                ForEach(0..<7, id: \.self) { index in
                    Capsule()
                        .fill(index < filled ? bar : Color.primary.opacity(0.12))
                        .frame(height: emphasized ? 10 : 8)
                }
            }
        }
    }
}

private struct WeekCalendarStrip: View {
    let weekStart: Date

    var body: some View {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: weekStart)
        let today = calendar.startOfDay(for: Date())
        HStack(spacing: 4) {
            ForEach(0..<7, id: \.self) { index in
                let day = calendar.date(byAdding: .day, value: index, to: start) ?? start
                let isToday = calendar.isDate(day, inSameDayAs: today)
                let passed = day <= today
                VStack(spacing: 4) {
                    Text(day.formatted(.dateTime.weekday(.narrow)))
                        .font(.system(.caption2, design: .rounded, weight: isToday ? .bold : .medium))
                        .foregroundStyle(isToday ? AppColors.calorie : Color.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Capsule()
                        .fill(passed ? AppColors.calorie : Color.primary.opacity(0.12))
                        .frame(height: isToday ? 8 : 6)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(.top, 10)
    }
}

struct WeeklyChallengeParticipantRow: View {
    let participant: WeeklyChallengeParticipant
    let category: WeeklyChallengeCategory
    let isViewer: Bool
    let onReport: () -> Void
    let onBlock: () -> Void
    @AppStorage(EnergyUnit.storageKey) private var energyUnit = EnergyUnit.defaultUnit

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 16)
        let medal = participant.rank <= 3 ? WeeklyChallengeRankBadge.fill(for: participant.rank) : nil
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 10) {
                WeeklyChallengeRankBadge(place: participant.rank)
                WeeklyChallengeParticipantIdentity(participant: participant)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            WeeklyChallengeSocialLink(participant: participant)
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(WeeklyChallengeParticipantScore.text(for: participant, category: category, energyUnit: energyUnit))
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    if isViewer {
                        Text(WeeklyChallengeL10n.text("You"))
                            .font(.system(.caption, design: .rounded, weight: .bold))
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                if !isViewer {
                    Menu {
                        Button(WeeklyChallengeL10n.text("Report"), action: onReport)
                        Button(WeeklyChallengeL10n.text("Block"), role: .destructive, action: onBlock)
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.body.weight(.semibold))
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(
                        WeeklyChallengeL10n.format("More actions for %1$@", participant.displayName)
                    )
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background((isViewer ? AppColors.calorie : medal ?? Color.primary).opacity(isViewer || medal != nil ? 0.10 : 0.035), in: shape)
        .overlay {
            shape.strokeBorder((isViewer ? AppColors.calorie : medal ?? Color.primary).opacity(isViewer || medal != nil ? 0.45 : 0.10))
                .allowsHitTesting(false)
        }
    }
}

private struct WeeklyChallengeRankBadge: View {
    let place: Int
    var diameter: CGFloat = 36

    var body: some View {
        Text(place.formatted())
            .font(.system(diameter > 40 ? .title3 : .subheadline, design: .rounded, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(foreground)
            .padding(.horizontal, 7)
            .padding(.vertical, 5)
            .frame(minWidth: diameter, minHeight: diameter)
            .fixedSize()
            .background(fill, in: Capsule())
    }

    private var fill: Color { Self.fill(for: place) }

    private var foreground: Color { Self.foreground(for: place) }

    static func fill(for place: Int) -> Color {
        switch place {
        case 1: Color(red: 1, green: 0.76, blue: 0.03)
        case 2: Color(red: 0.84, green: 0.84, blue: 0.84)
        case 3: Color(red: 0.88, green: 0.63, blue: 0.35)
        default: Color.primary.opacity(0.08)
        }
    }

    static func foreground(for place: Int) -> Color {
        switch place {
        case 1: Color(red: 0.23, green: 0.16, blue: 0)
        case 2: Color(red: 0.17, green: 0.17, blue: 0.17)
        case 3: Color(red: 0.23, green: 0.13, blue: 0.03)
        default: Color.primary
        }
    }
}

private struct WeeklyChallengeParticipantIdentity: View {
    let participant: WeeklyChallengeParticipant

    var body: some View {
        Text(participant.displayName)
            .font(.system(.body, design: .rounded, weight: .semibold))
            .lineLimit(nil)
            .fixedSize(horizontal: false, vertical: true)
    }
}

private struct WeeklyChallengeSocialLink: View {
    let participant: WeeklyChallengeParticipant

    var body: some View {
        if let platform = participant.socialPlatform,
           let handle = participant.socialHandle, !handle.isEmpty,
           let url = platform.profileURL(handle: handle) {
            Link(destination: url) {
                HStack(spacing: 8) {
                    Image(platform == .x ? "challenge-social-x" : "challenge-social-instagram")
                        .resizable()
                        .frame(width: 16, height: 16)
                        .accessibilityHidden(true)
                    Text(verbatim: "@\(handle)")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                        .lineLimit(nil)
                        .fixedSize(horizontal: false, vertical: true)
                        .environment(\.layoutDirection, .leftToRight)
                }
                .foregroundStyle(AppColors.calorie)
                .frame(minWidth: 48, minHeight: 48, alignment: .leading)
                .contentShape(Rectangle())
            }
            .accessibilityLabel(WeeklyChallengeL10n.format("Open @%1$@ on %2$@", handle, platform.title))
        }
    }
}

private enum WeeklyChallengeParticipantScore {
    static func text(
        for participant: WeeklyChallengeParticipant,
        category: WeeklyChallengeCategory,
        energyUnit: EnergyUnit
    ) -> String {
        if participant.balancedDays != nil {
            if category == .overall {
                return String(localized: "Points: \(participant.overallPoints)", table: "ChallengeHub")
            }
            return String(localized: "Days: \(participant.score)", table: "ChallengeHub")
        }
        switch category {
        case .overall:
            return WeeklyChallengeL10n.format(
                "%1$@ / 28 pts",
                participant.overallPoints.formatted()
            )
        case .activity:
            return WeeklyChallengeL10n.activityDays(
                participant.activityDays,
                burnKcal: participant.activityKcal,
                energyUnit: energyUnit
            )
        case .nutrition, .consistency, .hydration:
            return WeeklyChallengeL10n.format(
                "%1$@ / 7 days",
                participant.score.formatted()
            )
        }
    }
}

private struct WeeklyChallengeProfileSheet: View {
    @Environment(\.dismiss) private var dismiss

    let mode: WeeklyChallengeProfileSheetMode
    let score: WeeklyChallengeScore
    let isSaving: Bool
    let serverErrorMessage: String?
    let onSave: (WeeklyChallengeProfileInput, Bool, Bool) async -> Bool

    @State private var displayName: String
    @State private var socialPlatform: WeeklyChallengeSocialPlatform?
    @State private var socialHandle: String
    @State private var acceptedRules = false
    @State private var eligibilityAccepted = false
    @State private var validationMessage: String?

    init(
        mode: WeeklyChallengeProfileSheetMode,
        profile: WeeklyChallengePublicProfile?,
        score: WeeklyChallengeScore,
        isSaving: Bool,
        serverErrorMessage: String?,
        onSave: @escaping (WeeklyChallengeProfileInput, Bool, Bool) async -> Bool
    ) {
        self.mode = mode
        self.score = score
        self.isSaving = isSaving
        self.serverErrorMessage = serverErrorMessage
        self.onSave = onSave
        _displayName = State(initialValue: profile?.displayName ?? "")
        _socialPlatform = State(initialValue: profile?.socialPlatform)
        _socialHandle = State(initialValue: profile?.socialHandle ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(WeeklyChallengeL10n.text("Public Profile")) {
                    TextField(
                        WeeklyChallengeL10n.text("Display name"),
                        text: $displayName
                    )
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .accessibilityHint(
                        WeeklyChallengeL10n.text("Use 2 to 40 letters or numbers.")
                    )

                    Picker(
                        WeeklyChallengeL10n.text("Social link"),
                        selection: $socialPlatform
                    ) {
                        Text(WeeklyChallengeL10n.text("No social link"))
                            .tag(Optional<WeeklyChallengeSocialPlatform>.none)
                        ForEach(WeeklyChallengeSocialPlatform.allCases) { platform in
                            Text(platform.title).tag(Optional(platform))
                        }
                    }

                    if socialPlatform != nil {
                        TextField(
                            WeeklyChallengeL10n.text("Handle without @"),
                            text: $socialHandle
                        )
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityHint(
                            WeeklyChallengeL10n.text("Enter a handle, not a URL.")
                        )
                    }

                    Text(
                        WeeklyChallengeL10n.text(
                            "Your display name and optional one social handle are visible to joined participants."
                        )
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                Section(WeeklyChallengeL10n.text("Shared Weekly Totals")) {
                    Text(
                        WeeklyChallengeL10n.text(
                            "Calculated locally. No raw food, water, workout, Health, body-weight, or weight-loss data is uploaded."
                        )
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    WeeklyChallengePayloadView(score: score)
                }

                if mode == .join {
                    Section(WeeklyChallengeL10n.text("Eligibility & Community")) {
                        Toggle(
                            WeeklyChallengeL10n.text("I am 18 or older"),
                            isOn: $eligibilityAccepted
                        )
                        Toggle(
                            WeeklyChallengeL10n.text("I agree to the Community Rules"),
                            isOn: $acceptedRules
                        )
                        Link(
                            WeeklyChallengeL10n.text("Read Community Rules"),
                            destination: URL(string: "https://fud-ai.app/terms.html#community-rules")!
                        )
                        Text(
                            WeeklyChallengeL10n.text(
                                "Fud AI does not read or upload your date of birth for this confirmation."
                            )
                        )
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    }
                }

                if let message = validationMessage ?? serverErrorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                            .accessibilityLabel(
                                WeeklyChallengeL10n.format("Error: %1$@", message)
                            )
                    }
                }
            }
            .navigationTitle(
                mode == .join
                    ? WeeklyChallengeL10n.text("Join Weekly Challenge")
                    : WeeklyChallengeL10n.text("Edit Public Profile")
            )
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isSaving)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(WeeklyChallengeL10n.text("Cancel")) { dismiss() }
                        .disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(
                        mode == .join
                            ? WeeklyChallengeL10n.text("Join")
                            : WeeklyChallengeL10n.text("Save")
                    ) {
                        save()
                    }
                    .disabled(
                        isSaving
                            || (mode == .join && (!acceptedRules || !eligibilityAccepted))
                    )
                }
            }
            .overlay {
                if isSaving {
                    ProgressView()
                        .padding(18)
                        .flashPanel()
                        .accessibilityLabel(WeeklyChallengeL10n.text("Saving public profile"))
                }
            }
        }
    }

    private func save() {
        switch WeeklyChallengeProfileValidator.validated(
            displayName: displayName,
            socialPlatform: socialPlatform,
            socialHandle: socialHandle
        ) {
        case .failure(let error):
            validationMessage = error.message
        case .success(let input):
            validationMessage = nil
            Task {
                if await onSave(input, acceptedRules, eligibilityAccepted) {
                    dismiss()
                }
            }
        }
    }
}

private struct WeeklyChallengeReportView: View {
    @Environment(\.dismiss) private var dismiss

    let participant: WeeklyChallengeParticipant
    let isSubmitting: Bool
    let serverErrorMessage: String?
    let onSubmit: (WeeklyChallengeReportReason, String) async -> Bool

    @State private var reason: WeeklyChallengeReportReason = .inappropriateName
    @State private var details = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(WeeklyChallengeL10n.text("Participant")) {
                    Text(participant.displayName)
                }

                Section(WeeklyChallengeL10n.text("Reason")) {
                    Picker(WeeklyChallengeL10n.text("Report reason"), selection: $reason) {
                        ForEach(WeeklyChallengeReportReason.allCases) { reason in
                            Text(reason.title).tag(reason)
                        }
                    }
                    .pickerStyle(.inline)
                }

                Section {
                    TextEditor(text: $details)
                        .frame(minHeight: 100)
                        .onChange(of: details) { _, value in
                            let sanitized = WeeklyChallengeReportDetailsValidator.sanitizedInput(value)
                            if sanitized != value { details = sanitized }
                        }
                        .accessibilityLabel(WeeklyChallengeL10n.text("Optional report details"))
                } header: {
                    Text(WeeklyChallengeL10n.text("Optional details"))
                } footer: {
                    Text(
                        WeeklyChallengeL10n.format(
                            "%1$@ / 300",
                            WeeklyChallengeReportDetailsValidator
                                .codePointCount(details)
                                .formatted()
                        )
                    )
                }

                Text(
                    WeeklyChallengeL10n.text(
                        "Reports contain only the participant ID, selected reason, and optional details you type. No health or food data is included."
                    )
                )
                .font(.footnote)
                .foregroundStyle(.secondary)

                if let serverErrorMessage {
                    Text(serverErrorMessage)
                        .foregroundStyle(.red)
                        .accessibilityLabel(
                            WeeklyChallengeL10n.format("Error: %1$@", serverErrorMessage)
                        )
                }
            }
            .navigationTitle(WeeklyChallengeL10n.text("Report Participant"))
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(isSubmitting)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(WeeklyChallengeL10n.text("Cancel")) { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(WeeklyChallengeL10n.text("Send Report")) {
                        Task {
                            if await onSubmit(reason, details) { dismiss() }
                        }
                    }
                    .disabled(isSubmitting)
                }
            }
        }
    }
}

private struct WeeklyChallengeBlockedParticipantsView: View {
    private struct BlockedParticipant: Identifiable {
        let id: String
        let name: String
    }

    @Environment(\.dismiss) private var dismiss
    let store: WeeklyChallengeStore
    let knownParticipants: [WeeklyChallengeParticipant]

    private var blocked: [BlockedParticipant] {
        store.blockedParticipantIDs.sorted().map { id in
            let name = knownParticipants.first { $0.participantId == id }?.displayName
                ?? WeeklyChallengeL10n.text("Unknown participant")
            return BlockedParticipant(id: id, name: name)
        }
    }

    var body: some View {
        NavigationStack {
            List(blocked, id: \.id) { participant in
                HStack {
                    Text(participant.name)
                    Spacer()
                    Button(WeeklyChallengeL10n.text("Unblock")) {
                        store.unblock(participantID: participant.id)
                    }
                    .buttonStyle(.bordered)
                }
            }
            .overlay {
                if blocked.isEmpty {
                    ContentUnavailableView {
                        Label(
                            WeeklyChallengeL10n.text("No Blocked Participants"),
                            systemImage: "person.crop.circle.badge.checkmark"
                        )
                    }
                }
            }
            .navigationTitle(WeeklyChallengeL10n.text("Blocked Participants"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(WeeklyChallengeL10n.text("Done")) { dismiss() }
                }
            }
        }
    }
}
