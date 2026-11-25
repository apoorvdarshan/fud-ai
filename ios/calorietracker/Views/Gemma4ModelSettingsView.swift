import SwiftUI

struct Gemma4ModelSettingsView: View {
    let onAvailabilityChange: () -> Void

    let modelManager: Gemma4LocalModelManager
    @State private var showDeleteConfirmation = false

    init(
        modelManager: Gemma4LocalModelManager = .shared,
        onAvailabilityChange: @escaping () -> Void
    ) {
        self.modelManager = modelManager
        self.onAvailabilityChange = onAvailabilityChange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            FlashAdaptiveStack {
                Label(modelManager.variant.name, systemImage: "cpu")
                    .labelStyle(.titleAndIcon)
                    .font(.body.weight(.medium))
                Text(statusLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(statusColor)
            }

            Text(statusDescription)
                .font(.caption)
                .foregroundStyle(.secondary)
            if modelManager.isEligible && modelManager.variant == .e4b {
                Text("Requires a 12 GB RAM device").font(.caption).foregroundStyle(.secondary)
            }

            if let progress = modelManager.downloadProgress {
                ProgressView(value: progress)
                    .tint(AppColors.calorie)
                    .accessibilityLabel(localized("gemma.downloadProgress", "Gemma 4 download progress"))
                    .accessibilityValue(Text(progress, format: .percent))
            } else if modelManager.state == .verifying {
                ProgressView()
                    .tint(AppColors.calorie)
                    .accessibilityLabel(localized("gemma.verifyingDownload", "Verifying Gemma 4 download"))
            }

            FlashAdaptiveStack {
                if isDownloading {
                    Button(localized("common.cancel", "Cancel"), role: .cancel) {
                        modelManager.cancelDownload()
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(downloadButtonTitle) {
                        Task {
                            if modelManager.isDownloaded {
                                await modelManager.prepare()
                            } else {
                                await modelManager.download()
                            }
                        }
                    }
                    .buttonStyle(FlashActionButtonStyle())
                    .tint(AppColors.calorie)
                    .disabled(
                        !modelManager.isEligible
                            || modelManager.state.isBusy
                            || modelManager.state == .ready
                    )
                }

                if modelManager.hasStoredData {
                    Button(localized("common.delete", "Delete"), role: .destructive) {
                        showDeleteConfirmation = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(modelManager.state.isBusy)
                }
            }

            FlashAdaptiveStack(spacing: 12) {
                Link("Apache 2.0", destination: Gemma4LocalModelManager.licenseURL)

                Link(destination: modelManager.variant.sourceURL) {
                    Label(localized("gemma.modelSource", "Model source"), systemImage: "arrow.up.right.square")
                        .labelStyle(.titleAndIcon)
                }
                .accessibilityIdentifier(modelManager.variant == .e2b
                    ? "settings.localModel.gemma.source" : "settings.localModel.gemmaE4B.source")
            }
            .buttonStyle(FlashModelLinkStyle())
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
        .onAppear { modelManager.refresh() }
        .onChange(of: modelManager.isSelectable) { _, _ in
            onAvailabilityChange()
        }
        .confirmationDialog(
            localized("gemma.deleteTitle", "Delete Gemma 4?"),
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(localized("common.deleteModel", "Delete Model"), role: .destructive) {
                deleteModel()
            }
            Button(localized("common.cancel", "Cancel"), role: .cancel) { }
        } message: {
            Text(localized(
                "gemma.deleteMessage",
                "The downloaded model and its compiled cache will be removed from this iPhone. You can download it again later."
            ))
        }
    }

    private var statusLabel: String {
        guard modelManager.isEligible else {
            if modelManager.variant == .e4b { return String(localized: "Requires a 12 GB RAM device") }
            return localized("gemma.requires8GB", "Requires an 8 GB RAM device")
        }
        switch modelManager.state {
        case .notDownloaded:
            return localized("common.notDownloaded", "Not downloaded")
        case .downloaded:
            return localized("common.downloaded", "Downloaded")
        case .downloading(let progress):
            return "\(Int(progress * 100))%"
        case .verifying:
            return localized("common.verifying", "Verifying")
        case .preparing:
            return localized("common.preparing", "Preparing")
        case .ready:
            return localized("common.ready", "Ready")
        case .generating:
            return localized("common.inUse", "In use")
        case .failed:
            return localized("common.needsAttention", "Needs attention")
        }
    }

    private var statusColor: Color {
        guard modelManager.isEligible else { return .secondary }
        switch modelManager.state {
        case .downloaded, .ready:
            return .green
        case .failed:
            return .red
        case .notDownloaded, .downloading, .verifying, .preparing, .generating:
            return .secondary
        }
    }

    private var statusDescription: String {
        guard modelManager.isEligible else {
            if modelManager.variant == .e4b {
                return String(localized: "3.66 GB download. This offline image and text model requires a device with at least 12 GB of RAM.")
            }
            return localized(
                "gemma.unsupportedDescription",
                "This on-device image and text model is available on iPhones with at least 8 GB of physical memory."
            )
        }

        switch modelManager.state {
        case .notDownloaded:
            if modelManager.variant == .e4b {
                return String(localized: "3.66 GB download. Fud AI checks for an additional 1 GB of free installation headroom.")
            }
            return localized(
                "gemma.downloadDescription",
                "2.59 GB download. Fud AI checks for an additional 1 GB of free installation headroom."
            )
        case .downloaded:
            if let size = modelManager.installedSizeDescription {
                return LocalModelStrings.format(
                    "gemma.storedWithSize",
                    defaultValue: "Verified and stored locally (%@). Tap Prepare now, or it will load on first use.",
                    size
                )
            }
            return localized(
                "gemma.stored",
                "Verified and stored locally. Tap Prepare now, or it will load on first use."
            )
        case .downloading:
            return localized(
                "gemma.downloading",
                "Downloading the pinned Gemma 4 model… Keep Fud AI open until it finishes."
            )
        case .verifying:
            return localized(
                "gemma.checking",
                "Checking the exact file size and SHA-256 before installation…"
            )
        case .preparing:
            return localized(
                "gemma.compiling",
                "Compiling and loading the LiteRT-LM Metal runtime…"
            )
        case .ready:
            return localized(
                "gemma.readyDescription",
                "Ready for private, offline food images and text."
            )
        case .generating:
            return localized("gemma.generating", "Generating locally on this iPhone…")
        case .failed(let message):
            return message
        }
    }

    private var downloadButtonTitle: String {
        switch modelManager.state {
        case .ready:
            return localized("common.ready", "Ready")
        default:
            return modelManager.isDownloaded
                ? localized("common.prepare", "Prepare")
                : localized("common.download", "Download")
        }
    }

    private var isDownloading: Bool {
        if case .downloading = modelManager.state { return true }
        return false
    }

    private func deleteModel() {
        do {
            try modelManager.delete()
            AIProviderSettings.replaceDeletedLocalGemmaSelections(
                provider: modelManager.variant == .e4b ? .gemma4E4BLocal : .gemma4Local
            )
            onAvailabilityChange()
        } catch {
            modelManager.refresh()
        }
    }

    private func localized(_ key: String, _ defaultValue: String) -> String {
        LocalModelStrings.text(key, defaultValue: defaultValue)
    }
}
