import Foundation
import SwiftUI
import UIKit

/// The progress part of the analyzing sheet: the current stage, the elapsed time and —
/// only when the local history supports it — how long this request usually takes.
///
/// There is no percentage and no countdown: the providers answer with one complete
/// document, so a percentage would be a guess. The estimate is a band of durations this
/// device measured itself, and it disappears once the request runs past it.
struct AnalysisProgressView: View {
    let state: AnalysisProgressState

    var body: some View {
        // A timeline redraws the elapsed time once per second. It needs no Combine
        // publisher and no timer that has to be invalidated, and it is anchored at the
        // start of the analysis so a new run does not continue the old ticking.
        TimelineView(.periodic(from: state.startedAt, by: 1)) { _ in
            content(elapsedSeconds: state.elapsedSeconds())
        }
    }

    @ViewBuilder
    private func content(elapsedSeconds: Int) -> some View {
        VStack(spacing: 6) {
            Text(stageLabel)
                .font(.headline)
                // The calorie accent is only about 3.5:1 on white. Use the primary text
                // color for the stage and keep the accent for the spinner and icons.
                .foregroundStyle(.primary)
                .multilineTextAlignment(.center)

            Text(elapsedLabel(elapsedSeconds))
                .font(.subheadline)
                // Secondary is below AA in the light theme; 80 % primary stays readable
                // in both light and dark mode.
                .foregroundStyle(Color.primary.opacity(0.8))
                .monospacedDigit()

            if let estimate = estimateLabel(elapsedSeconds: elapsedSeconds) {
                Text(estimate)
                    .font(.caption)
                    .foregroundStyle(Color.primary.opacity(0.7))
            }
        }
        // Keep the spoken label stable (the stage) and put the ticking time into the
        // value. Without this split VoiceOver would hear the whole sentence again on
        // every tick. `updatesFrequently` lets it poll the value instead of interrupting
        // the user once per second.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(stageLabel)
        .accessibilityValue(accessibilityValue(elapsedSeconds: elapsedSeconds))
        .accessibilityAddTraits(.updatesFrequently)
        .onChange(of: stageLabel) { _, _ in
            guard UIAccessibility.isVoiceOverRunning else { return }
            UIAccessibility.post(notification: .announcement, argument: stageLabel)
        }
    }

    /// The label of the current stage.
    private var stageLabel: String {
        // A barcode asks a product database, not an AI provider. No provider stage
        // may be shown for it.
        if state.kind == .barcode {
            return String(localized: "Looking up the barcode…")
        }
        switch state.stage {
        case .preparing:
            return state.kind == .image
                ? String(localized: "Preparing your photo…")
                : String(localized: "Preparing your request…")
        case .requesting:
            // A model that runs on this iPhone never talks to a provider.
            return state.runsOnDevice
                ? String(localized: "Analyzing on this iPhone…")
                : String(localized: "Waiting for the AI provider…")
        case .responding:
            return String(localized: "Reading the response…")
        case .validating:
            return String(localized: "Checking the nutrition data…")
        case .fallback:
            if state.attempt > 1 {
                return String(
                    format: String(localized: "Trying the backup model (attempt %d)…"),
                    state.attempt
                )
            }
            return String(localized: "Trying the backup model…")
        }
    }

    private func elapsedLabel(_ elapsedSeconds: Int) -> String {
        String(format: String(localized: "%d s elapsed"), elapsedSeconds)
    }

    /// The "usually about N s" hint, or nil when there is not enough local history or
    /// the request has already run past the estimate (which would make it misleading).
    private func estimateLabel(elapsedSeconds: Int) -> String? {
        guard let estimate = state.estimateSeconds else { return nil }
        guard state.estimateStillApplies(elapsedSeconds: elapsedSeconds) else { return nil }
        if estimate.lowerBound == estimate.upperBound {
            return String(format: String(localized: "Usually takes about %d s"), estimate.lowerBound)
        }
        return String(
            format: String(localized: "Usually takes about %d–%d s"),
            estimate.lowerBound,
            estimate.upperBound
        )
    }

    /// The changing part of the announcement: running time and, while it still fits,
    /// the estimate. The stage stays in the accessibility label.
    private func accessibilityValue(elapsedSeconds: Int) -> String {
        var parts = [elapsedLabel(elapsedSeconds)]
        if let estimate = estimateLabel(elapsedSeconds: elapsedSeconds) {
            parts.append(estimate)
        }
        return parts.joined(separator: ", ")
    }
}
