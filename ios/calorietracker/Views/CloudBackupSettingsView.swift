import SwiftUI

struct CloudBackupSettingsSection: View {
    @Environment(CloudBackupService.self) private var backup
    @State private var showEnableConfirm = false
    @State private var showRestoreChoice = false
    @State private var showDeleteConfirm = false
    @State private var errorMessage: String?

    var body: some View {
        Section {
            Toggle(isOn: Binding(
                get: { backup.enabled },
                set: { on in
                    if on { showEnableConfirm = true }
                    else { backup.enabled = false }
                }
            )) {
                Label("iCloud Backup", systemImage: "icloud")
            }
            .accessibilityIdentifier("settings.cloudBackup.toggle")
            .disabled(backup.busy)

            if let last = backup.lastAt {
                (Text("Last backup: ") + Text(shortDate(last)))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("settings.cloudBackup.lastBackup")
            }

            if backup.enabled {
                Button("Back up now") { Task { await run { try await backup.backupNow() } } }
                    .accessibilityIdentifier("settings.cloudBackup.backupNow")
                    .disabled(backup.busy)
                Button("Restore now") { Task { await run { try await backup.restoreNow() } } }
                    .accessibilityIdentifier("settings.cloudBackup.restoreNow")
                    .disabled(backup.busy)
                Button("Delete cloud backup", role: .destructive) { showDeleteConfirm = true }
                    .accessibilityIdentifier("settings.cloudBackup.delete")
                    .disabled(backup.busy)
            }
        } footer: {
            Text("Off until you turn it on. Uses the iCloud account on this iPhone. API keys stay on the device.")
        }
        .listRowBackground(AppColors.appCard)
        .alert("iCloud Backup", isPresented: $showEnableConfirm) {
            Button("Turn On") {
                Task { await turnOn() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Uploads your diary, workouts, fasting, photos, and settings to your iCloud. API keys stay on the phone.")
        }
        .alert("Restore backup?", isPresented: $showRestoreChoice) {
            Button("Restore") {
                Task { await run { try await backup.restoreNow() } }
            }
            Button("Keep this phone") {
                Task { await run { try await backup.backupNow() } }
            }
        } message: {
            Text("Restore it, or keep this phone.")
        }
        .alert("Delete cloud backup?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                Task { await run { try await backup.deleteCloudBackup() } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Removes the Fud AI file from iCloud. This iPhone is unchanged.")
        }
        .alert("iCloud Backup", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func turnOn() async {
        do {
            try await backup.checkAccount()
            await backup.refreshCloudPresence()
            if backup.hasCloudBackup {
                showRestoreChoice = true
            } else {
                try await backup.backupNow()
            }
        } catch {
            backup.enabled = false
            errorMessage = error.localizedDescription
        }
    }

    private func run(_ work: () async throws -> Void) async {
        do {
            try await work()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func shortDate(_ iso: String) -> String {
        guard let date = ISO8601DateFormatter().date(from: iso) else { return iso }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}
