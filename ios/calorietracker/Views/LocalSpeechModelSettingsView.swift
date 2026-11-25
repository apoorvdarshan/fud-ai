import SwiftUI

struct LocalSpeechModelSettingsView: View {
    let manager: LocalSpeechModelManager
    let onAvailabilityChange: () -> Void
    var inUse = false
    @State private var confirmDelete = false

    private var model: LocalSpeechModel { manager.model }
    private var size: String { ByteCountFormatter.string(fromByteCount: model.downloadBytes, countStyle: .file) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(model.name, systemImage: "waveform.badge.mic").font(.body.weight(.medium))
            description
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(status).font(.caption).foregroundStyle(.secondary)
            if model.isEligible() {
                Text("Requires a \(model.minimumRAM) GB RAM device").font(.caption).foregroundStyle(.secondary)
            }
            if let progress = manager.progress {
                ProgressView(value: progress)
                    .accessibilityLabel(Text("Model download progress"))
            }
            if let error = manager.errorMessage {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            FlashAdaptiveStack {
                if manager.progress != nil {
                    Button("Cancel", role: .cancel) { manager.cancel() }.buttonStyle(.bordered)
                } else if !manager.isInstalled {
                    Button("Download") { Task { await manager.download() } }
                        .buttonStyle(FlashActionButtonStyle())
                        .disabled(!model.isEligible())
                }
                if manager.hasFiles, manager.progress == nil {
                    Button("Delete", role: .destructive) { confirmDelete = true }.buttonStyle(.bordered)
                        .disabled(inUse)
                }
            }
            FlashAdaptiveStack(spacing: 12) {
                Link(model.licenseName, destination: model.licenseURL)
                Link(destination: model.source) {
                    Label("Model source", systemImage: "arrow.up.right.square")
                }
            }
            .buttonStyle(FlashModelLinkStyle())
        }
        .padding(.vertical, 4)
        .onAppear { manager.refresh() }
        .onChange(of: manager.isInstalled) { _, _ in onAvailabilityChange() }
        .confirmationDialog("Delete Model?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete Model", role: .destructive) {
                manager.delete()
                if !manager.isInstalled {
                    switch model {
                    case .parakeetV3: SpeechSettings.replaceDeletedLocalSelections(.parakeetV3)
                    case .qwen3ASR: SpeechSettings.replaceDeletedLocalSelections(.qwen3ASR)
                    case .lfm3Audio: break
                    }
                    onAvailabilityChange()
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    @ViewBuilder private var description: some View {
        switch model {
        case .parakeetV3: Text(SpeechProvider.parakeetV3.description)
        case .qwen3ASR: Text(SpeechProvider.qwen3ASR.description)
        case .lfm3Audio:
            Text("English only, one turn at a time. This preview does not use diary tools or the cloud.", tableName: "OfflineVoice")
        }
    }

    private var status: String {
        if !model.isEligible() {
            return String(localized: "Requires a \(model.minimumRAM) GB RAM device · \(size) download")
        }
        if manager.isInstalled { return LocalModelStrings.text("common.ready", defaultValue: "Ready") }
        return String(localized: "\(size) download")
    }
}
