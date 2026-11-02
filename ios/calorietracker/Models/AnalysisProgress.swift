import Foundation
import Observation

/// The kind of request an analysis runs, used for the local duration history.
enum AnalysisKind: String {
    /// A typed description (typed in or dictated by voice).
    case text
    /// One or more photos.
    case image
    /// A barcode lookup that does not call an AI provider.
    case barcode
}

/// The truthful stages of an AI meal analysis.
///
/// The providers used by Fud AI answer with one complete JSON document, so there is no
/// token or byte level progress to report. These stages therefore only describe work
/// that actually happened, and the UI shows an indeterminate indicator for them.
enum AnalysisStage: String {
    /// Preparing the request: image processing and prompt assembly.
    case preparing
    /// The request is with the provider; waiting for the provider's answer.
    case requesting
    /// The provider answered and the response is being read.
    case responding
    /// The nutrition data is being checked and the review screen is prepared.
    case validating
    /// The primary route failed and the backup model is being tried.
    case fallback
}

/// How the running analysis ended.
enum AnalysisOutcome: String {
    case running
    case complete
    case failed
    case cancelled
}

/// The state of one analysis, as shown by the progress UI.
///
/// - `estimateSeconds` is the usual duration of this provider/model combination, taken
///   from the local duration history. It is nil when there is not enough history, so the
///   UI shows the elapsed time only instead of a fabricated countdown.
/// - `startedAt` is only the anchor for the once-per-second timeline redraw. The elapsed
///   time itself uses `startedAtUptime`, because the wall clock can jump when the time
///   zone or NTP changes while an analysis is running.
struct AnalysisProgressState: Equatable {
    var kind: AnalysisKind
    var stage: AnalysisStage
    var attempt: Int = 1
    var provider: AIProvider?
    var model: String?
    var estimateSeconds: ClosedRange<Int>?
    var startedAt: Date = Date()
    var startedAtUptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    var outcome: AnalysisOutcome = .running

    /// Milliseconds since the analysis started, measured on the monotonic system uptime.
    func elapsedMilliseconds(nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Int {
        max(0, Int((nowUptime - startedAtUptime) * 1000))
    }

    /// Seconds since the analysis started, measured on the monotonic system uptime.
    func elapsedSeconds(nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Int {
        elapsedMilliseconds(nowUptime: nowUptime) / 1000
    }

    /// True while the local estimate still fits the running request.
    ///
    /// The hint is hidden once the request runs past the upper end of the band: a
    /// "usually about 10 s" next to a 40 s runtime would be a lie.
    func estimateStillApplies(elapsedSeconds: Int) -> Bool {
        guard let estimate = estimateSeconds else { return false }
        return elapsedSeconds <= estimate.upperBound
    }

    /// True when this finished run is a clean local-history sample.
    ///
    /// A backup run's total includes the failed primary wait, so storing it under the
    /// backup provider would make that model look slower than it is. Barcode lookups
    /// and hosted runs have no provider/model to attribute the sample to.
    var canRecordDuration: Bool {
        outcome == .complete &&
            kind != .barcode &&
            attempt == 1 &&
            provider != nil &&
            model?.isEmpty == false
    }

    /// True when the request runs on this iPhone instead of at a remote provider.
    ///
    /// The UI uses this to say "Analyzing on this iPhone…" instead of naming an AI
    /// provider that is never contacted.
    var runsOnDevice: Bool {
        provider?.runsOnDevice == true
    }
}

/// One progress event of an analysis.
struct AnalysisProgressUpdate {
    var stage: AnalysisStage
    /// 1 for the primary request, 2 or more for a backup model. Nil keeps the
    /// current attempt, so a stage-only update cannot reset a running fallback.
    var attempt: Int? = nil
    /// Provider that is handling the current attempt, known once the request is sent.
    var provider: AIProvider?
    /// Model that is handling the current attempt.
    var model: String?
    /// Local history based estimate of the usual duration, in seconds.
    var estimateSeconds: ClosedRange<Int>?
}

/// Receives progress events while an analysis runs.
typealias AnalysisProgressReporter = (AnalysisProgressUpdate) -> Void

/// Tracks the state of one analysis so the UI can show an honest progress indicator.
///
/// The tracker only records work that really happened: it never invents a percentage and
/// it never counts down. The elapsed time comes from the start date, and the optional
/// estimate is a band of durations this device measured for the same provider and model.
/// The running time uses the monotonic system uptime, not the wall clock.
@Observable
@MainActor
final class AnalysisProgressTracker {
    private(set) var state: AnalysisProgressState?

    /// Monotonic clock used for the running time. Tests can inject a fake clock.
    private let now: () -> TimeInterval

    init(now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.now = now
    }

    /// Identifies the current run.
    ///
    /// Every `start` and every `reset` moves to a new id. Reports and results that
    /// belong to an older run are ignored, so a late answer of a cancelled request can
    /// never overwrite the progress of the request that runs now.
    private(set) var runID: Int = 0

    /// Starts a new analysis and returns its initial state.
    ///
    /// Any state of a previous run is dropped, so the UI never mixes two analyses.
    @discardableResult
    func start(kind: AnalysisKind) -> AnalysisProgressState {
        runID += 1
        let initial = AnalysisProgressState(
            kind: kind,
            stage: .preparing,
            startedAt: Date(),
            startedAtUptime: now()
        )
        state = initial
        return initial
    }

    /// A reporter that belongs to the run that is current right now.
    ///
    /// The returned closure silently drops updates once a newer run started. That keeps
    /// a late answer of an older request from moving the progress of the current one.
    func reporter() -> AnalysisProgressReporter {
        let id = runID
        return { [weak self] update in
            guard let self, self.runID == id else { return }
            self.onProgress(update)
        }
    }

    /// Applies a progress event reported by the analysis layer.
    @discardableResult
    func onProgress(_ update: AnalysisProgressUpdate) -> AnalysisProgressState {
        guard var running = state else {
            return start(kind: .text)
        }
        // The first ending wins: a late stage report after cancel/error/complete must
        // not make the finished run look active again.
        guard running.outcome == .running else { return running }
        // A new attempt (the backup model) brings its own estimate: the duration of
        // the primary says nothing about the fallback. A stage-only update has no
        // attempt, so validation after a fallback keeps that attempt and its estimate
        // instead of silently falling back to the primary.
        let nextAttempt = update.attempt ?? running.attempt
        let attemptChanged = update.attempt != nil && update.attempt != running.attempt
        // A repair request can switch back to the primary model without starting a
        // new attempt. Its predecessor's estimate then belongs to another model and
        // must not be shown for this wait.
        let modelChanged =
            (update.provider != nil && update.provider != running.provider) ||
                (update.model != nil && update.model != running.model)
        let estimate: ClosedRange<Int>?
        if attemptChanged || modelChanged {
            estimate = update.estimateSeconds
        } else {
            estimate = update.estimateSeconds ?? running.estimateSeconds
        }
        running.stage = update.stage
        running.attempt = nextAttempt
        if let provider = update.provider { running.provider = provider }
        if let model = update.model { running.model = model }
        running.estimateSeconds = estimate
        state = running
        return running
    }

    /// Marks the analysis as finished successfully.
    @discardableResult
    func complete() -> AnalysisProgressState { finish(.complete) }

    /// Marks the analysis as failed. The reason is shown by the caller's error UI.
    @discardableResult
    func fail() -> AnalysisProgressState { finish(.failed) }

    /// Marks the analysis as cancelled by the user.
    @discardableResult
    func cancel() -> AnalysisProgressState { finish(.cancelled) }

    /// Ends one specific run. A result of an older run is ignored.
    @discardableResult
    func finish(_ outcome: AnalysisOutcome, runID id: Int) -> AnalysisProgressState? {
        guard id == runID, var running = state else { return nil }
        // First outcome wins, also for a result that already carries a run id.
        if running.outcome == .running {
            running.outcome = outcome
        }
        state = running
        return running
    }

    /// Drops the tracked analysis so a later one starts from a clean state.
    ///
    /// The run id moves on as well, so pending reports of the dropped run are ignored.
    func reset() {
        runID += 1
        state = nil
    }

    /// Seconds since the analysis started, or 0 when nothing is running.
    var elapsedSeconds: Int {
        state?.elapsedSeconds(nowUptime: now()) ?? 0
    }

    private func finish(_ outcome: AnalysisOutcome) -> AnalysisProgressState {
        guard var running = state else {
            return start(kind: .text)
        }
        // The first ending wins: a request the user cancelled must not turn into a
        // failure afterwards when the cancelled task reports its error.
        if running.outcome == .running {
            running.outcome = outcome
        }
        state = running
        return running
    }
}
