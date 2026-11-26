import SwiftUI

/// Settings > App Settings > Tab Bar: show or hide Progress, Coach and Workouts.
struct TabBarSettingsView: View {
    @AppStorage(TabBarSettings.hiddenTabsKey) private var hiddenTabsRaw = ""
    @AppStorage(WorkoutTabMode.storageKey) private var workoutTabModeRaw = WorkoutTabMode.defaultMode.rawValue

    var body: some View {
        Form {
            Section {
                ForEach(HideableTab.allCases) { tab in
                    Toggle(isOn: isShown(tab)) {
                        Label {
                            Text(title(for: tab))
                        } icon: {
                            Image(systemName: icon(for: tab))
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier("settings.tabBar.\(tab.rawValue)")
                }
            } header: {
                Text("Show in Tab Bar")
            } footer: {
                Text("Home and Settings are always shown.")
            }
        }
        .navigationTitle("Tab Bar")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func isShown(_ tab: HideableTab) -> Binding<Bool> {
        Binding(
            get: { !TabBarSettings.decode(hiddenTabsRaw).contains(tab) },
            set: { shown in
                var hidden = TabBarSettings.decode(hiddenTabsRaw)
                if shown {
                    hidden.remove(tab)
                } else {
                    hidden.insert(tab)
                }
                hiddenTabsRaw = TabBarSettings.encode(hidden)
            }
        )
    }

    private func title(for tab: HideableTab) -> LocalizedStringKey {
        switch tab {
        case .progress: "Progress"
        case .coach: "Coach"
        case .workouts: "Workouts"
        }
    }

    /// The same symbols the tab bar uses.
    private func icon(for tab: HideableTab) -> String {
        switch tab {
        case .progress: "chart.bar.fill"
        case .coach: "bubble.left.and.bubble.right.fill"
        case .workouts: WorkoutTabMode.mode(for: workoutTabModeRaw).tabIcon
        }
    }
}
