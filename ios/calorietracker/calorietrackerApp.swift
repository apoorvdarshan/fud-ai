//
//  calorietrackerApp.swift
//  calorietracker
//
//  Created by Apoorv Darshan on 05/02/26.
//

import SwiftUI
import HealthKit
import WidgetKit
import RevenueCat

@main
struct calorietrackerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var foodStore = FoodStore()
    @State private var weightStore = WeightStore()
    @State private var bodyFatStore = BodyFatStore()
    @State private var bodyMeasurementStore = BodyMeasurementStore()
    @State private var notificationManager = NotificationManager()
    @State private var healthKitManager = HealthKitManager()
    @State private var profileStore = ProfileStore()
    @State private var chatStore = ChatStore()
    @State private var waterStore = WaterStore()
    @State private var fastingStore = FastingStore()
    @State private var strengthWorkoutStore = StrengthWorkoutStore()
    @State private var weeklyChallengeStore = WeeklyChallengeStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("appearanceMode") private var appearanceMode = "system"
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @AppStorage(AppThemeColor.storageKey) private var appThemeColorRaw = AppThemeColor.defaultColor.rawValue
    @Environment(\.scenePhase) private var scenePhase
    @State private var isAutoRefreshingAdaptiveGoals = false

    private var colorScheme: ColorScheme? {
        switch appearanceMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    init() {
        // Tip-jar IAPs are tracked through RevenueCat (public SDK key, safe to ship).
        Purchases.logLevel = .warn
        Purchases.configure(withAPIKey: "appl_kOERxwXPyEUPZVCKhuuuNnUuGUZ")
        // Derive the split height/weight unit prefs from the legacy useMetric flag
        // before any view reads them.
        UnitPreferenceMigration.runIfNeeded()
        AIProviderSettings.migrateLegacyGeminiModelsIfNeeded()
        SpeechSettings.migrateMatchingPrimaryProviderIfNeeded()
        if CommandLine.arguments.contains("--reset-onboarding") {
            UserDefaults.standard.removeObject(forKey: "hasCompletedOnboarding")
            UserDefaults.standard.removeObject(forKey: "userProfile")
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if hasCompletedOnboarding {
                    ContentView()
                        .environment(foodStore)
                        .environment(weightStore)
                        .environment(bodyFatStore)
                        .environment(bodyMeasurementStore)
                        .environment(notificationManager)
                        .environment(healthKitManager)
                        .environment(profileStore)
                        .environment(chatStore)
                        .environment(waterStore)
                        .environment(fastingStore)
                        .environment(strengthWorkoutStore)
                        .environment(weeklyChallengeStore)
                } else {
                    OnboardingView(hasCompletedOnboarding: $hasCompletedOnboarding)
                        .environment(notificationManager)
                        .environment(foodStore)
                        .environment(weightStore)
                        .environment(bodyFatStore)
                        .environment(bodyMeasurementStore)
                        .environment(healthKitManager)
                        .environment(profileStore)
                        .environment(chatStore)
                        .environment(waterStore)
                        .environment(fastingStore)
                }
            }
            .tint(AppThemeColor.color(for: appThemeColorRaw).color)
            .preferredColorScheme(colorScheme)
            .onAppear {
                AppThemeColor.applyAppIconIfNeeded(for: AppThemeColor.color(for: appThemeColorRaw))
            }
            .onChange(of: appThemeColorRaw) { _, newValue in
                AppThemeColor.applyAppIconIfNeeded(for: AppThemeColor.color(for: newValue))
            }
            .onReceive(NotificationCenter.default.publisher(for: .userProfileDidChange)) { _ in
                refreshWidgetSnapshot()
            }
            .task {
                await weeklyChallengeStore.retryPendingDeletionIfNeeded()
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                Task {
                    await notificationManager.refreshAuthorizationStatus()
                }
                if notificationsEnabled, let profile = UserProfile.load() {
                    notificationManager.rescheduleDataDependentNotifications(
                        foodStore: foodStore, weightStore: weightStore, bodyFatStore: bodyFatStore, profile: profile
                    )
                }
                if hasCompletedOnboarding {
                    wireUpHealthKit()
                    // Re-wire on every scene-active so the widget refresh callback
                    // is connected for users who completed onboarding before this
                    // hook existed (the .onChange(hasCompletedOnboarding) branch
                    // only fires on the false→true transition, never on cold launch).
                    wireUpFoodStoreCallback()
                    refreshAdaptiveGoalsIfNeeded()
                }
                // Refresh on scene-active so widgets roll over at midnight even
                // without an explicit food change.
                refreshWidgetSnapshot()
            }
        }
        .onChange(of: hasCompletedOnboarding) { _, completed in
            if completed {
                // New installs start with Energy Burn on, and Adaptive Goals on —
                // UNLESS the user hand-tuned their plan during onboarding, since
                // adaptive's first weekly run would overwrite those numbers.
                // Existing users are untouched — these keys are only written here
                // and by the Settings toggles. Onboarding just calculated goals, so
                // mark the weekly adaptive check as done; the first run lands next week.
                if !UserDefaults.standard.bool(forKey: "onboardingPlanEdited") {
                    UserDefaults.standard.set(true, forKey: AdaptiveGoalSettings.enabledKey)
                }
                UserDefaults.standard.set(true, forKey: EnergyBurnSettings.enabledKey)
                AdaptiveGoalSettings.markCheckedToday()
                wireUpFoodStoreCallback()
                wireUpHealthKit()
                // Seed the user's first WeightEntry from their onboarding-entered profile
                // weight. Used to be seeded in WeightStore.init with .default fallback,
                // which produced a 70 kg phantom entry for every fresh user.
                if let profile = UserProfile.load() {
                    weightStore.seedInitialWeightFromProfileIfEmpty(profile.weightKg)
                    // Same idea for body fat — only when the user actually
                    // entered a value during the onboarding body-fat step.
                    // Skipped when bodyFatPercentage is nil (the "No" branch).
                    if let bf = profile.bodyFatPercentage {
                        bodyFatStore.seedInitialBodyFatFromProfileIfEmpty(bf)
                    }
                }
                refreshWidgetSnapshot()
            }
        }
    }

    private func wireUpHealthKit() {
        guard UserDefaults.standard.bool(forKey: "healthKitEnabled") else { return }

        // Re-request authorization if new HealthKit types were added since last auth.
        // Backfill is idempotent (per-entry HealthKit existence check), so it's safe to call
        // in both branches without duplicating already-synced history.
        if healthKitManager.needsReauthorization {
            Task { [healthKitManager, foodStore] in
                _ = await healthKitManager.requestAuthorization()
                healthKitManager.backfillNutritionIfNeeded(
                    entries: foodStore.entries,
                    currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
                )
                runBodyMeasurementBackfills()
            }
        } else {
            healthKitManager.backfillNutritionIfNeeded(
                entries: foodStore.entries,
                currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
            )
            runBodyMeasurementBackfills()
        }

        healthKitManager.onBodyMeasurementsChanged = { [weightStore, bodyFatStore] weightKg, weightDate, weightFudaiID, heightCm, bodyFat, bodyFatDate, bodyFatFudaiID, dob, sex in
            guard var profile = UserProfile.load() else { return }
            var changed = false

            if let kg = weightKg, let date = weightDate {
                // If the HK sample was written by our app (has fudai_weight_id), never re-add
                // from the observer: either the entry still exists in the store (duplicate) or the
                // user just deleted it and the HK delete hasn't propagated yet (would resurrect it).
                // External HK samples (Apple Watch, scale, Health app) have no fudai_weight_id;
                // those we dedup by same-day + same-value.
                let shouldAdd: Bool
                if weightFudaiID != nil {
                    shouldAdd = false
                } else {
                    let calendar = Calendar.current
                    let alreadyLogged = weightStore.entries.contains {
                        calendar.isDate($0.date, inSameDayAs: date) && abs($0.weightKg - kg) < 0.01
                    }
                    shouldAdd = !alreadyLogged
                }
                if shouldAdd {
                    weightStore.addEntry(WeightEntry(date: date, weightKg: kg))
                }
                // Only sync profile.weightKg from the HK observer when the latest sample came
                // from OUTSIDE our app. For our own samples, WeightStore.addEntry / deleteEntry
                // already syncs profile — updating it here again can revert a just-made edit if
                // HK hasn't indexed the write yet and returns an older sample of ours.
                if weightFudaiID == nil, abs(profile.weightKg - kg) > 0.01 {
                    profile.weightKg = kg
                    changed = true
                }
            }
            if let cm = heightCm, abs(profile.heightCm - cm) > 0.1 {
                profile.heightCm = cm
                changed = true
            }
            if let bf = bodyFat, let date = bodyFatDate {
                // Same dedup discipline as weight: skip our own writes
                // (fudai_bodyfat_id present), and dedup external samples by
                // same-day + same-fraction so re-firing the observer can't
                // duplicate a smart-scale reading we already imported once.
                let shouldAdd: Bool
                if bodyFatFudaiID != nil {
                    shouldAdd = false
                } else {
                    let calendar = Calendar.current
                    let alreadyLogged = bodyFatStore.entries.contains {
                        calendar.isDate($0.date, inSameDayAs: date) && abs($0.bodyFatFraction - bf) < 0.001
                    }
                    shouldAdd = !alreadyLogged
                }
                if shouldAdd {
                    bodyFatStore.addEntry(BodyFatEntry(date: date, bodyFatFraction: bf))
                    // BodyFatStore.addEntry already syncs profile.bodyFatPercentage
                    // for any new entry, so no extra profile.save() needed here.
                } else if bodyFatFudaiID == nil,
                          profile.bodyFatPercentage == nil || abs((profile.bodyFatPercentage ?? 0) - bf) > 0.001 {
                    // External sample we already had (dedup hit) — but the
                    // profile cache somehow drifted. Realign without creating
                    // a duplicate entry. Skip when it's our own sample (HK
                    // may briefly return a stale write of ours during indexing).
                    profile.bodyFatPercentage = bf
                    changed = true
                }
            }
            if let d = dob {
                let calendar = Calendar.current
                if !calendar.isDate(profile.birthday, inSameDayAs: d) {
                    profile.birthday = d
                    changed = true
                }
            }
            if let s = sex {
                // Only sync when HealthKit gave us an actual male/female reading.
                // HKBiologicalSex.notSet (sim default + users who never set it
                // in Health) and .other should NOT overwrite the user's
                // onboarding-chosen gender — without this guard, the observer
                // silently flipped gender to "Other" right after onboarding
                // finished on the simulator since HK returns .notSet there.
                let mapped: Gender?
                switch s {
                case .male: mapped = .male
                case .female: mapped = .female
                default: mapped = nil
                }
                if let mapped, profile.gender != mapped {
                    profile.gender = mapped
                    changed = true
                }
            }
            if changed { profile.save() }
        }

        healthKitManager.startBodyMeasurementObserver()

        weightStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeWeight(for: entry)
        }

        weightStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteWeight(entryID: entryID)
        }

        bodyFatStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeBodyFat(for: entry)
        }

        bodyFatStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteBodyFat(entryID: entryID)
        }

        foodStore.onEntryAdded = { [healthKitManager] entry in
            healthKitManager.writeNutrition(for: entry)
        }

        foodStore.onEntryDeleted = { [healthKitManager] entryID in
            healthKitManager.deleteNutrition(entryID: entryID)
        }

        foodStore.onEntryUpdated = { [healthKitManager] entry in
            healthKitManager.updateNutrition(for: entry)
        }
    }

    /// Pulls historical weight + body-fat samples out of HealthKit on first
    /// HK enable so the Progress chart starts populated for users who already
    /// have years of scale data in Apple Health (Withings, Renpho, Apple
    /// Watch, manual entries, etc.). One-shot per typesVersion — see
    /// HealthKitManager.{weight,bodyFat}BackfillVersionKey.
    private func runBodyMeasurementBackfills() {
        healthKitManager.backfillWeightFromHealthKitIfNeeded(
            existing: { [weightStore] in weightStore.entries },
            importBatch: { [weightStore] entries in weightStore.importExternalEntries(entries) }
        )
        healthKitManager.backfillBodyFatFromHealthKitIfNeeded(
            existing: { [bodyFatStore] in bodyFatStore.entries },
            importBatch: { [bodyFatStore] entries in bodyFatStore.importExternalEntries(entries) }
        )
        // Restore the food log from the app's own HK nutrition samples after a
        // reinstall / phone reset wiped the local store. The merge path fires
        // onEntriesChanged (widgets/notifications) but not onEntryAdded, so
        // restored entries are NOT re-written to HealthKit.
        healthKitManager.restoreFoodEntriesFromHealthKitIfNeeded(
            existingIDs: { [foodStore] in Set(foodStore.entries.map(\.id)) },
            importBatch: { [foodStore] entries in foodStore.mergeWithCloudEntries(entries) }
        )
        healthKitManager.synchronizeWorkoutBurnsWithHealthKit(
            existing: { [strengthWorkoutStore] in strengthWorkoutStore.workoutBurnSessions },
            mergeBatch: { [strengthWorkoutStore] sessions in
                strengthWorkoutStore.importWorkoutBurnSessions(sessions)
            }
        )
    }

    private func wireUpFoodStoreCallback() {
        foodStore.onEntriesChanged = { [notificationManager, foodStore, weightStore, bodyFatStore] in
            if UserDefaults.standard.bool(forKey: "notificationsEnabled"),
               let profile = UserProfile.load() {
                notificationManager.rescheduleDataDependentNotifications(
                    foodStore: foodStore, weightStore: weightStore, bodyFatStore: bodyFatStore, profile: profile
                )
            }
            if let profile = UserProfile.load() {
                WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
            }
        }
        waterStore.onEntriesChanged = { [foodStore] in
            guard let profile = UserProfile.load() else { return }
            WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
        }
        fastingStore.onSessionsChanged = { [notificationManager, fastingStore] in
            let enabled = UserDefaults.standard.bool(forKey: "notificationsEnabled")
                && UserDefaults.standard.bool(forKey: FastingSettings.enabledKey)
                && UserDefaults.standard.bool(forKey: FastingSettings.notificationEnabledKey)
            notificationManager.scheduleFastingGoal(enabled: enabled, session: fastingStore.activeSession)
        }
        WatchSnapshotSync.shared.configureWaterLogging { [waterStore] request in
            guard UserDefaults.standard.bool(forKey: WaterSettings.enabledKey) else {
                return .disabled
            }
            let duplicate = waterStore.entries.contains(where: { $0.id == request.id })
            guard waterStore.add(
                id: request.id,
                milliliters: request.milliliters,
                on: request.date
            ) != nil else {
                return .invalid
            }
            return duplicate ? .duplicate : .added
        }
        // Install workout callbacks even when Health sync is currently off.
        // Writes honor the toggle, while deletion can still clean up a sample
        // exported before the user disabled Health sync.
        strengthWorkoutStore.onWorkoutBurnUpserted = { [healthKitManager] session in
            healthKitManager.updateWorkoutBurn(for: session)
        }
        strengthWorkoutStore.onWorkoutBurnDeleted = { [healthKitManager] sessionID in
            healthKitManager.deleteWorkoutBurn(sessionID: sessionID)
        }
    }

    private func refreshWidgetSnapshot() {
        guard let profile = UserProfile.load() else {
            // No profile — onboarding not complete OR data was wiped. Clear the
            // shared snapshot so the widget shows an empty day instead of stale
            // numbers from a previous profile.
            WidgetSnapshot.clear()
            WidgetCenter.shared.reloadAllTimelines()
            return
        }
        WidgetSnapshotWriter.publish(foods: foodStore.entries, profile: profile)
    }

    @MainActor
    private func refreshAdaptiveGoalsIfNeeded() {
        guard !isAutoRefreshingAdaptiveGoals else { return }
        guard UserDefaults.standard.bool(forKey: AdaptiveGoalSettings.enabledKey) else { return }
        guard AdaptiveGoalSettings.shouldCheckThisWeek() else { return }
        guard let profile = UserProfile.load() else { return }

        isAutoRefreshingAdaptiveGoals = true
        let healthOn = UserDefaults.standard.bool(forKey: "healthKitEnabled")
        let energyBurnOn = UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey)
        let heightMetric = HeightUnit.current == .cm
        let weightMetric = WeightUnit.current == .kg
        let weights = weightStore.entries
        let foods = foodStore.entries
        let bodyFatEntries = bodyFatStore.entries
        let workoutSessions = strengthWorkoutStore.completedSessions
        let bodyMeasurements = bodyMeasurementStore.entries
        Task {
            defer { Task { @MainActor in isAutoRefreshingAdaptiveGoals = false } }

            // Adaptive Goals = the same AI calculation the Recalculate button runs, on a weekly
            // timer. Energy Burn (when on) anchors maintenance to measured Apple Health burn.
            var healthEnergy = energyBurnOn && healthOn
                ? await healthKitManager.fetchRecentEnergyHistory(days: 14)
                : []
            let adaptiveStillOn = UserDefaults.standard.bool(forKey: AdaptiveGoalSettings.enabledKey)
            let healthStillOn = UserDefaults.standard.bool(forKey: "healthKitEnabled")
            let energyBurnStillOn = UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey)
            guard adaptiveStillOn,
                  healthStillOn == healthOn,
                  energyBurnStillOn == energyBurnOn
            else { return }
            // Defense in depth for the privacy boundary: if either source switch is off, no
            // fetched Health history can enter the evidence pack.
            if !healthStillOn || !energyBurnStillOn { healthEnergy = [] }
            let measuredTdee = HealthKitManager.energySummary(
                from: healthEnergy,
                requestedDays: 14
            ).map {
                $0.totalAverageCalories ?? (Int(profile.bmr.rounded()) + $0.activeAverageCalories)
            }
            let evidence = GoalEvidence.build(
                foods: foods,
                weights: weights,
                bodyFatEntries: bodyFatEntries,
                workoutSessions: workoutSessions,
                bodyMeasurements: bodyMeasurements,
                healthEnergy: healthEnergy,
                profile: profile
            )
            do {
                let result = try await GeminiService.calculateGoals(
                    profile: profile,
                    measuredTdee: measuredTdee,
                    measurement: bodyMeasurements.sorted { $0.date > $1.date }.first,
                    evidence: evidence,
                    heightMetric: heightMetric,
                    weightMetric: weightMetric
                )
                // The provider call can take seconds. Never overwrite profile/target edits the
                // user made while it was in flight; the next foreground can recalculate afresh.
                guard UserDefaults.standard.bool(forKey: AdaptiveGoalSettings.enabledKey),
                      UserDefaults.standard.bool(forKey: "healthKitEnabled") == healthOn,
                      UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey) == energyBurnOn,
                      let latest = UserProfile.load(), latest == profile
                else { return }
                AdaptiveGoalSettings.savePreviousTargetsIfNeeded(from: latest)
                var next = latest
                next.customCalories = result.calories
                next.customProtein = result.protein
                next.customCarbs = result.carbs
                next.customFat = result.fat
                next.autoBalanceMacro = nil
                next.clearLocks()
                next.save()
                AdaptiveGoalSettings.markCheckedToday()
            } catch {
                // AI unavailable — keep existing goals; mark checked so we don't retry every open.
                if UserDefaults.standard.bool(forKey: AdaptiveGoalSettings.enabledKey),
                   UserDefaults.standard.bool(forKey: "healthKitEnabled") == healthOn,
                   UserDefaults.standard.bool(forKey: EnergyBurnSettings.enabledKey) == energyBurnOn {
                    AdaptiveGoalSettings.markCheckedToday()
                }
            }
        }
    }
}
