//
//  calorietrackerApp.swift
//  calorietracker
//
//  Created by Apoorv Darshan on 05/02/26.
//

import SwiftUI
import HealthKit

@main
struct calorietrackerApp: App {
    @State private var foodStore = FoodStore()
    @State private var weightStore = WeightStore()
    @State private var notificationManager = NotificationManager()
    @State private var healthKitManager = HealthKitManager()
    @State private var profileStore = ProfileStore()
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("appearanceMode") private var appearanceMode = "system"
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @Environment(\.scenePhase) private var scenePhase

    private var colorScheme: ColorScheme? {
        switch appearanceMode {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    init() {
        if CommandLine.arguments.contains("--reset-onboarding") {
            UserDefaults.standard.removeObject(forKey: "hasCompletedOnboarding")
            UserDefaults.standard.removeObject(forKey: "userProfile")
        }
        APIKeyManager.migrateIfNeeded()
    }

    var body: some Scene {
        WindowGroup {
            if hasCompletedOnboarding {
                ContentView()
                    .environment(foodStore)
                    .environment(weightStore)
                    .environment(notificationManager)
                    .environment(healthKitManager)
                    .environment(profileStore)
                    .preferredColorScheme(colorScheme)
            } else {
                OnboardingView(hasCompletedOnboarding: $hasCompletedOnboarding)
                    .environment(notificationManager)
                    .environment(foodStore)
                    .environment(weightStore)
                    .environment(healthKitManager)
                    .environment(profileStore)
                    .preferredColorScheme(colorScheme)
            }
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                Task {
                    await notificationManager.refreshAuthorizationStatus()
                }
                if notificationsEnabled, let profile = UserProfile.load() {
                    notificationManager.rescheduleDataDependentNotifications(
                        foodStore: foodStore, profile: profile
                    )
                }
                if hasCompletedOnboarding {
                    wireUpHealthKit()
                }
            }
        }
        .onChange(of: hasCompletedOnboarding) { _, completed in
            if completed {
                wireUpFoodStoreCallback()
                wireUpHealthKit()
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
            }
        } else {
            healthKitManager.backfillNutritionIfNeeded(
                entries: foodStore.entries,
                currentEntryIDs: { Set(foodStore.entries.map(\.id)) }
            )
        }

        healthKitManager.onBodyMeasurementsChanged = { [weightStore] weightKg, weightDate, weightFudaiID, heightCm, bodyFat, dob, sex in
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
            if let bf = bodyFat {
                if profile.bodyFatPercentage == nil || abs((profile.bodyFatPercentage ?? 0) - bf) > 0.001 {
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
                let mapped: Gender = s == .male ? .male : s == .female ? .female : .other
                if profile.gender != mapped {
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

    private func wireUpFoodStoreCallback() {
        foodStore.onEntriesChanged = { [notificationManager, foodStore] in
            guard UserDefaults.standard.bool(forKey: "notificationsEnabled"),
                  let profile = UserProfile.load() else { return }
            notificationManager.rescheduleDataDependentNotifications(
                foodStore: foodStore, profile: profile
            )
        }
    }
}
