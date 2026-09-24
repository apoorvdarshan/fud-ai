import SwiftUI

struct WeeklyChallengeView: View {
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @Environment(WeeklyChallengeStore.self) private var store
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage(WaterSettings.enabledKey) private var waterTrackingEnabled = false
    @AppStorage(WaterSettings.dailyGoalKey) private var waterDailyGoal = WaterSettings.defaultDailyGoalMl

    @State private var category: WeeklyChallengeCategory = .overall
    @State private var profileSheetMode: WeeklyChallengeProfileSheetMode?
    @State private var reportTarget: WeeklyChallengeParticipant?
    @State private var showBlockedParticipants = false
    @State private var showLeaveConfirmation = false
    @State private var showReportConfirmation = false

    private struct RefreshIdentity: Hashable {
        let category: WeeklyChallengeCategory
        let score: WeeklyChallengeScore
        let isJoined: Bool
        let hasPendingDeletion: Bool
    }

    private var localScore: WeeklyChallengeScore {
        WeeklyChallengeAggregator.score(
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
        WeeklyChallengeWeek.containing(.now)
    }

    private var displayedLeaderboard: WeeklyChallengeLeaderboardResponse? {
        store.leaderboardFor(category: category, weekStart: localScore.weekStart)
    }

    private var displayedViewer: WeeklyChallengeParticipant? {
        store.viewerFor(category: category, weekStart: localScore.weekStart)
    }

    var body: some View {
        Group {
            if store.hasPendingDeletion {
                pendingDeletionView
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
                isJoined: store.isJoined,
                hasPendingDeletion: store.hasPendingDeletion
            )
        ) {
            if store.hasPendingDeletion {
                await store.retryPendingDeletionIfNeeded()
            } else if store.isJoined {
                await store.refresh(category: category, score: localScore)
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(60))
                    guard !Task.isCancelled, store.isJoined else { return }
                    await store.refresh(category: category, score: localScore)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active else { return }
            Task {
                if store.hasPendingDeletion {
                    await store.retryPendingDeletionIfNeeded()
                } else if store.isJoined {
                    await store.refresh(category: category, score: localScore)
                }
            }
        }
        .sheet(item: $profileSheetMode) { mode in
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
        .sheet(item: $reportTarget) { participant in
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
        .sheet(isPresented: $showBlockedParticipants) {
            WeeklyChallengeBlockedParticipantsView(
                store: store,
                knownParticipants: store.knownParticipants
            )
        }
        .alert(
            WeeklyChallengeL10n.text("Leave Weekly Challenge?"),
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
            Text(
                WeeklyChallengeL10n.text(
                    "Your public profile, weekly scores, and challenge reports will be deleted from Fud AI. Your private food, water, and workout history stays on this device."
                )
            )
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

    private var joinIntroduction: some View {
        ScrollView {
            VStack(spacing: 18) {
                Image(systemName: "trophy.fill")
                    .font(.system(size: 44, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityHidden(true)

                Text(WeeklyChallengeL10n.text("Weekly Challenge"))
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
                .buttonStyle(.borderedProminent)
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
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
        }
    }

    private var joinedView: some View {
        ScrollView {
            LazyVStack(spacing: 14) {
                challengeHeader
                categorySelector

                if let response = displayedLeaderboard {
                    leaderboardRows(response)
                } else if store.isRefreshing {
                    ProgressView()
                        .padding(.vertical, 32)
                        .accessibilityLabel(WeeklyChallengeL10n.text("Loading leaderboard"))
                } else {
                    Text(WeeklyChallengeL10n.text("No leaderboard results are available yet."))
                        .font(.system(.body, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.vertical, 28)
                }

                if let viewer = displayedViewer {
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

                WeeklyChallengePointsExplanationView()

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
            await store.refresh(category: category, score: localScore)
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
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(weekRangeText)
                    .font(.system(.headline, design: .rounded, weight: .bold))
                Text(statusText)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(store.isOffline ? .orange : .secondary)
            }
            Spacer()
            if store.isRefreshing {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel(WeeklyChallengeL10n.text("Updating leaderboard"))
            }
        }
        .padding(14)
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var weekRangeText: String {
        let start = currentWeek.start.formatted(.dateTime.month(.abbreviated).day())
        let end = currentWeek.end.formatted(.dateTime.month(.abbreviated).day().year())
        return WeeklyChallengeL10n.format("%1$@ – %2$@", start, end)
    }

    private var statusText: String {
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
        let viewerID = displayedViewer?.participantId ?? store.participantID
        var rows = response.rankings.filter {
            !store.isBlocked(participantID: $0.participantId)
        }
        if let viewer = response.viewer ?? displayedViewer,
           !rows.contains(where: { $0.participantId == viewer.participantId }),
           !store.isBlocked(participantID: viewer.participantId) {
            rows.append(viewer)
        }
        rows.sort { lhs, rhs in
            if lhs.rank != rhs.rank { return lhs.rank < rhs.rank }
            return lhs.participantId < rhs.participantId
        }
        return rows
    }

    @ViewBuilder
    private func leaderboardRows(_ response: WeeklyChallengeLeaderboardResponse) -> some View {
        let viewerID = displayedViewer?.participantId ?? store.participantID
        let rows = orderedRankings(response)

        VStack(alignment: .leading, spacing: 0) {
            Text(WeeklyChallengeL10n.text("Rankings"))
                .font(.system(.title3, design: .rounded, weight: .bold))
                .padding(.horizontal, 14)
                .padding(.top, 14)
                .padding(.bottom, 6)

            if rows.isEmpty {
                Text(WeeklyChallengeL10n.text("No other participants are ranked yet."))
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
                    .padding(16)
            } else {
                let showPodium = rows.count >= 2
                let listRows = showPodium ? Array(rows.dropFirst(3)) : rows
                if showPodium {
                    WeeklyChallengePodium(
                        rankings: Array(rows.prefix(3)),
                        category: category,
                        viewerID: viewerID,
                        onReport: { participant in
                            store.clearError()
                            reportTarget = participant
                        },
                        onBlock: { store.block($0) }
                    )
                }
                ForEach(Array(listRows.enumerated()), id: \.element.id) { index, participant in
                    if index > 0 || showPodium {
                        Divider().padding(.leading, 62)
                    }
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
        }
        .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
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

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label(
                    WeeklyChallengeL10n.text("My Position"),
                    systemImage: "person.crop.circle.fill"
                )
                .font(.system(.headline, design: .rounded, weight: .bold))
                Spacer()
                Text(WeeklyChallengeL10n.format("#%1$@", participant.rank.formatted()))
                    .font(.system(.title3, design: .rounded, weight: .bold))
                    .foregroundStyle(AppColors.calorie)
            }

            WeeklyChallengeParticipantIdentity(participant: participant)

            Text(WeeklyChallengeParticipantScore.text(for: participant, category: category))
                .font(.system(.title3, design: .rounded, weight: .bold))

            HStack(spacing: 12) {
                WeeklyChallengeMiniStat(
                    title: WeeklyChallengeL10n.text("Activity"),
                    value: participant.activityDays
                )
                WeeklyChallengeMiniStat(
                    title: WeeklyChallengeL10n.text("Nutrition"),
                    value: participant.nutritionDays
                )
                WeeklyChallengeMiniStat(
                    title: WeeklyChallengeL10n.text("Consistency"),
                    value: participant.consistencyDays
                )
                WeeklyChallengeMiniStat(
                    title: WeeklyChallengeL10n.text("Hydration"),
                    value: participant.hydrationDays
                )
            }

            HStack {
                Button(WeeklyChallengeL10n.text("Edit Public Profile"), action: onEditProfile)
                    .buttonStyle(.bordered)
                Spacer()
                Button(
                    WeeklyChallengeL10n.text("Leave Challenge"),
                    role: .destructive,
                    action: onLeave
                )
                .buttonStyle(.bordered)
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

private struct WeeklyChallengeMiniStat: View {
    let title: String
    let value: Int

    var body: some View {
        VStack(spacing: 2) {
            Text("\(value)/7")
                .font(.system(.subheadline, design: .rounded, weight: .bold))
            Text(title)
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct WeeklyChallengePodium: View {
    let rankings: [WeeklyChallengeParticipant]
    let category: WeeklyChallengeCategory
    let viewerID: String?
    let onReport: (WeeklyChallengeParticipant) -> Void
    let onBlock: (WeeklyChallengeParticipant) -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            podiumColumn(rankings.count > 1 ? rankings[1] : nil, height: 64)
            podiumColumn(rankings.first, height: 96)
            podiumColumn(rankings.count > 2 ? rankings[2] : nil, height: 52)
        }
        .padding(.horizontal, 8)
        .padding(.top, 4)
    }

    @ViewBuilder
    private func podiumColumn(_ participant: WeeklyChallengeParticipant?, height: CGFloat) -> some View {
        if let participant {
            let isViewer = participant.isViewer || participant.participantId == viewerID
            VStack(spacing: 4) {
                ZStack {
                    WeeklyChallengeRankBadge(place: participant.rank)
                    if !isViewer {
                        HStack {
                            Spacer()
                            Menu {
                                Button(WeeklyChallengeL10n.text("Report")) { onReport(participant) }
                                Button(WeeklyChallengeL10n.text("Block"), role: .destructive) {
                                    onBlock(participant)
                                }
                            } label: {
                                Image(systemName: "ellipsis")
                                    .font(.body.weight(.semibold))
                                    .frame(width: 28, height: 28)
                            }
                            .accessibilityLabel(
                                WeeklyChallengeL10n.format("More actions for %1$@", participant.displayName)
                            )
                        }
                    }
                }
                Text(participant.displayName)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
                if isViewer {
                    Text(WeeklyChallengeL10n.text("You"))
                        .font(.system(.caption2, design: .rounded, weight: .bold))
                        .foregroundStyle(AppColors.calorie)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(AppColors.calorie.opacity(0.16), in: Capsule())
                }
                if let platform = participant.socialPlatform,
                   let handle = participant.socialHandle,
                   let url = platform.profileURL(handle: handle) {
                    Link(destination: url) {
                        Text(WeeklyChallengeL10n.format("@%1$@ · %2$@", handle, platform.title))
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(AppColors.calorie)
                            .lineLimit(1)
                    }
                }
                Text(WeeklyChallengeParticipantScore.text(for: participant, category: category))
                    .font(.system(.caption, design: .rounded, weight: .bold))
                    .foregroundStyle(WeeklyChallengeRankBadge.foreground(for: participant.rank))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity)
                    .frame(height: height, alignment: .top)
                    .padding(.top, 8)
                    .background(
                        WeeklyChallengeRankBadge.fill(for: participant.rank),
                        in: UnevenRoundedRectangle(
                            topLeadingRadius: 16,
                            topTrailingRadius: 16
                        )
                    )
                    .padding(.horizontal, 4)
            }
            .frame(maxWidth: .infinity, alignment: .bottom)
        } else {
            Color.clear.frame(maxWidth: .infinity).frame(height: height)
        }
    }
}

private struct WeeklyChallengeParticipantRow: View {
    let participant: WeeklyChallengeParticipant
    let category: WeeklyChallengeCategory
    let isViewer: Bool
    let onReport: () -> Void
    let onBlock: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            WeeklyChallengeRankBadge(place: participant.rank)

            WeeklyChallengeParticipantIdentity(participant: participant, isViewer: isViewer)

            Spacer(minLength: 4)

            Text(WeeklyChallengeParticipantScore.text(for: participant, category: category))
                .font(.system(.subheadline, design: .rounded, weight: .bold))
                .foregroundStyle(AppColors.calorie)
                .multilineTextAlignment(.trailing)

            if !isViewer {
                Menu {
                    Button(WeeklyChallengeL10n.text("Report"), action: onReport)
                    Button(
                        WeeklyChallengeL10n.text("Block"),
                        role: .destructive,
                        action: onBlock
                    )
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .font(.title3)
                        .frame(width: 32, height: 44)
                }
                .accessibilityLabel(
                    WeeklyChallengeL10n.format("More actions for %1$@", participant.displayName)
                )
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(isViewer ? AppColors.calorie.opacity(0.12) : Color.clear)
    }
}

private struct WeeklyChallengeRankBadge: View {
    let place: Int

    var body: some View {
        Text("\(place)")
            .font(.system(.subheadline, design: .rounded, weight: .bold))
            .foregroundStyle(foreground)
            .frame(width: 36, height: 36)
            .background(fill, in: Circle())
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
    var isViewer: Bool = false

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(participant.displayName)
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .lineLimit(1)
                if isViewer {
                    Text(WeeklyChallengeL10n.text("You"))
                        .font(.system(.caption2, design: .rounded, weight: .bold))
                        .foregroundStyle(AppColors.calorie)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(AppColors.calorie.opacity(0.16), in: Capsule())
                }
            }

            if let platform = participant.socialPlatform,
               let handle = participant.socialHandle,
               let url = platform.profileURL(handle: handle) {
                Link(destination: url) {
                    Text(WeeklyChallengeL10n.format("@%1$@ · %2$@", handle, platform.title))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(AppColors.calorie)
                        .lineLimit(1)
                }
                .accessibilityLabel(
                    WeeklyChallengeL10n.format(
                        "Open @%1$@ on %2$@",
                        handle,
                        platform.title
                    )
                )
            }
        }
    }
}

private enum WeeklyChallengeParticipantScore {
    static func text(
        for participant: WeeklyChallengeParticipant,
        category: WeeklyChallengeCategory
    ) -> String {
        switch category {
        case .overall:
            return WeeklyChallengeL10n.format(
                "%1$@ / 28 pts",
                participant.overallPoints.formatted()
            )
        case .activity:
            return WeeklyChallengeL10n.format(
                "%1$@ / 7 days\n%2$@ kcal",
                participant.activityDays.formatted(),
                participant.activityKcal.formatted()
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
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
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
