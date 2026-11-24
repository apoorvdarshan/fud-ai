import SwiftUI

struct WhisperBaseModelSettingsView: View {
    @Binding var selectedProvider: SpeechProvider
    let onAvailabilityChange: () -> Void

    @State private var modelManager = WhisperBaseModelManager.shared
    @State private var showDeleteConfirmation = false

    init(
        selectedProvider: Binding<SpeechProvider>,
        onAvailabilityChange: @escaping () -> Void = { }
    ) {
        self._selectedProvider = selectedProvider
        self.onAvailabilityChange = onAvailabilityChange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label(localized("whisper.name", "Whisper Base"), systemImage: "waveform.badge.mic")
                    .font(.body.weight(.medium))
                Spacer()
                Text(statusLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(statusColor)
            }

            Text(statusDescription)
                .font(.caption)
                .foregroundStyle(.secondary)

            if let progress = modelManager.downloadProgress {
                ProgressView(value: progress)
                    .tint(AppColors.calorie)
                    .accessibilityLabel(localized("whisper.downloadProgress", "Whisper Base download progress"))
                    .accessibilityValue(Text(progress, format: .percent))
            }

            HStack {
                if isDownloading {
                    Button(localized("common.cancel", "Cancel"), role: .cancel) {
                        modelManager.cancelDownload()
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(downloadButtonTitle) {
                        Task { await modelManager.download() }
                    }
                    .buttonStyle(FlashActionButtonStyle())
                    .tint(AppColors.calorie)
                    .disabled(modelManager.state.isBusy || modelManager.isDownloaded)
                }

                if modelManager.hasStoredData {
                    Button(localized("common.delete", "Delete"), role: .destructive) {
                        showDeleteConfirmation = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(modelManager.state.isBusy)
                }
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    attributionLinks
                }
                VStack(alignment: .leading, spacing: 6) {
                    attributionLinks
                }
            }
            .font(.caption)
        }
        .padding(.vertical, 4)
        .onAppear { modelManager.refresh() }
        .onChange(of: modelManager.isDownloaded) { _, _ in
            onAvailabilityChange()
        }
        .confirmationDialog(
            localized("whisper.deleteTitle", "Delete Whisper Base?"),
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(localized("common.deleteModel", "Delete Model"), role: .destructive) {
                deleteModel()
            }
            Button(localized("common.cancel", "Cancel"), role: .cancel) { }
        } message: {
            Text(localized(
                "whisper.deleteMessage",
                "The downloaded model will be removed from this iPhone. You can download it again later."
            ))
        }
    }

    private var statusLabel: String {
        switch modelManager.state {
        case .notDownloaded:
            localized("common.notDownloaded", "Not downloaded")
        case .downloaded:
            localized("common.downloaded", "Downloaded")
        case .downloading(let progress):
            "\(Int(progress * 100))%"
        case .preparing:
            localized("common.preparing", "Preparing")
        case .ready:
            localized("common.ready", "Ready")
        case .transcribing:
            localized("common.inUse", "In use")
        case .failed:
            localized("common.needsAttention", "Needs attention")
        }
    }

    private var statusColor: Color {
        switch modelManager.state {
        case .downloaded, .ready:
            .green
        case .failed:
            .red
        case .notDownloaded, .downloading, .preparing, .transcribing:
            .secondary
        }
    }

    private var statusDescription: String {
        switch modelManager.state {
        case .notDownloaded:
            localized("whisper.about", "About 147 MB. Runs fully on-device after download.")
        case .downloaded, .ready:
            if let size = modelManager.installedSizeDescription {
                LocalModelStrings.format(
                    "whisper.storedWithSize",
                    defaultValue: "Stored locally (%@). No audio leaves this iPhone.",
                    size
                )
            } else {
                localized("whisper.stored", "Stored locally. No audio leaves this iPhone.")
            }
        case .downloading:
            localized("whisper.downloading", "Downloading the multilingual Core ML model…")
        case .preparing:
            localized("whisper.optimizing", "Optimizing the model for this iPhone…")
        case .transcribing:
            localized("whisper.transcribing", "Transcribing locally…")
        case .failed(let message):
            message
        }
    }

    private var downloadButtonTitle: String {
        modelManager.isDownloaded
            ? localized("common.downloaded", "Downloaded")
            : localized("common.download", "Download")
    }

    private var isDownloading: Bool {
        if case .downloading = modelManager.state { return true }
        return false
    }

    @ViewBuilder
    private var attributionLinks: some View {
        Link(destination: WhisperBaseModelManager.modelSourceURL) {
            Label(
                localized("gemma.modelSource", "Model Source"),
                systemImage: "shippingbox"
            )
        }
        Link("Whisper Base · MIT", destination: WhisperBaseModelManager.modelLicenseURL)
        Link("WhisperKit 1.1.0 · MIT", destination: WhisperBaseModelManager.whisperKitLicenseURL)
    }

    private func deleteModel() {
        do {
            try modelManager.delete()
            SpeechSettings.replaceDeletedWhisperSelections()
            if selectedProvider == .whisperBase {
                selectedProvider = .nativeIOS
                SpeechSettings.selectedProvider = .nativeIOS
            }
            onAvailabilityChange()
        } catch {
            modelManager.refresh()
        }
    }

    private func localized(_ key: String, _ defaultValue: String) -> String {
        LocalModelStrings.text(key, defaultValue: defaultValue)
    }
}
