import Foundation
import Testing
@testable import calorietracker

/// Covers the progress model of the AI meal analysis (#635): the stage sequence, the
/// elapsed time, the fallback attempt, the ending of a run and the local duration
/// estimate.
@MainActor
struct AnalysisProgressTests {

    // MARK: - Stages

    @Test func startReportsThePreparingStage() {
        let tracker = AnalysisProgressTracker()
        let state = tracker.start(kind: .image)
        #expect(state.stage == .preparing)
        #expect(state.kind == .image)
        #expect(state.outcome == .running)
        #expect(state.attempt == 1)
    }

    @Test func stagesFollowTheAnalysisOrder() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        tracker.onProgress(AnalysisProgressUpdate(stage: .requesting, provider: .openai))
        tracker.onProgress(AnalysisProgressUpdate(stage: .responding))
        let state = tracker.onProgress(AnalysisProgressUpdate(stage: .validating))

        #expect(state.stage == .validating)
        #expect(state.provider == .openai)
    }

    @Test func theFallbackAttemptKeepsItsProviderAndModel() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        let state = tracker.onProgress(
            AnalysisProgressUpdate(
                stage: .fallback,
                attempt: 2,
                provider: .openrouter,
                model: "openai/gpt-5.6-luna"
            )
        )

        #expect(state.stage == .fallback)
        #expect(state.attempt == 2)
        #expect(state.provider == .openrouter)
        #expect(state.model == "openai/gpt-5.6-luna")
    }

    @Test func aBarcodeRunIsNeverAnAiRun() {
        let tracker = AnalysisProgressTracker()
        let state = tracker.start(kind: .barcode)

        #expect(state.kind == .barcode)
        #expect(state.provider == nil)
        #expect(state.model == nil)
        #expect(state.estimateSeconds == nil)
    }

    @Test func anOnDeviceModelIsNotACloudProvider() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        let state = tracker.onProgress(
            AnalysisProgressUpdate(stage: .requesting, provider: .appleIntelligence)
        )

        #expect(state.runsOnDevice)
        #expect(AnalysisProgressState(kind: .text, stage: .requesting, provider: .gemini).runsOnDevice == false)
    }

    // MARK: - Estimate

    @Test func theEstimateSurvivesLaterUpdatesThatDoNotCarryIt() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        tracker.onProgress(AnalysisProgressUpdate(stage: .requesting, estimateSeconds: 5...12))
        let state = tracker.onProgress(AnalysisProgressUpdate(stage: .responding))

        #expect(state.estimateSeconds == 5...12)
    }

    @Test func theBackupModelBringsItsOwnEstimate() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        tracker.onProgress(AnalysisProgressUpdate(stage: .requesting, estimateSeconds: 5...12))
        let state = tracker.onProgress(
            AnalysisProgressUpdate(stage: .fallback, attempt: 2, estimateSeconds: 3...7)
        )

        // The duration of the primary says nothing about the backup model.
        #expect(state.estimateSeconds == 3...7)
    }

    @Test func theBackupModelKeepsItsAttemptAndEstimateThroughValidation() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        tracker.onProgress(AnalysisProgressUpdate(stage: .requesting, estimateSeconds: 5...12))
        tracker.onProgress(
            AnalysisProgressUpdate(
                stage: .fallback,
                attempt: 2,
                provider: .openrouter,
                estimateSeconds: 20...40
            )
        )
        // Validation is a stage-only update and must not fall back to attempt 1.
        let state = tracker.onProgress(AnalysisProgressUpdate(stage: .validating))

        #expect(state.attempt == 2)
        #expect(state.estimateSeconds == 20...40)
        #expect(state.provider == .openrouter)
        #expect(state.canRecordDuration == false)
    }

    @Test func aCompletedPrimaryRunCanBecomeADurationSample() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        tracker.onProgress(
            AnalysisProgressUpdate(
                stage: .requesting,
                provider: .openai,
                model: "gpt-5.6-luna",
                estimateSeconds: 5...12
            )
        )
        let state = tracker.complete()

        #expect(state.canRecordDuration)
    }

    @Test func theEstimateIsHiddenOnceTheRequestRunsPastIt() {
        let state = AnalysisProgressState(kind: .text, stage: .requesting, estimateSeconds: 5...12)

        #expect(state.estimateStillApplies(elapsedSeconds: 0))
        #expect(state.estimateStillApplies(elapsedSeconds: 12))
        #expect(state.estimateStillApplies(elapsedSeconds: 13) == false)

        let withoutHistory = AnalysisProgressState(kind: .text, stage: .requesting)
        #expect(withoutHistory.estimateStillApplies(elapsedSeconds: 0) == false)
    }

    // MARK: - Ending of a run

    @Test func outcomesAreRecordedForSuccessFailureAndCancellation() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        #expect(tracker.complete().outcome == .complete)

        tracker.start(kind: .text)
        #expect(tracker.fail().outcome == .failed)

        tracker.start(kind: .text)
        #expect(tracker.cancel().outcome == .cancelled)
    }

    @Test func aFailedRunDoesNotStayRunning() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        tracker.onProgress(AnalysisProgressUpdate(stage: .requesting))

        let state = tracker.fail()

        // A timeout ends like any other error: the run is over, not stuck.
        #expect(state.outcome == .failed)
        #expect(state.outcome != .running)
    }

    @Test func aCancelledRunStaysCancelled() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)

        tracker.cancel()
        let state = tracker.fail()

        // The cancelled task may still report its error later; that must not turn the
        // cancellation into a failure.
        #expect(state.outcome == .cancelled)
    }

    @Test func aFinishedRunIgnoresLateReportsAndKeepsItsOutcome() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        let runID = tracker.runID
        tracker.complete()

        // A provider that answers late must not restart the finished stage display.
        tracker.onProgress(AnalysisProgressUpdate(stage: .responding))

        #expect(tracker.state?.stage == .preparing)
        // The first outcome wins, also when the result carries the run id.
        #expect(tracker.finish(.failed, runID: runID)?.outcome == .complete)
        #expect(tracker.state?.outcome == .complete)
    }

    @Test func resetDropsTheTrackedAnalysis() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        tracker.reset()
        #expect(tracker.state == nil)
        #expect(tracker.elapsedSeconds == 0)
    }

    // MARK: - Races between start, finish, cancel and reset

    @Test func aLateReportOfAnOlderRunIsIgnored() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        let oldReporter = tracker.reporter()

        // A second analysis starts while the first one is still winding down.
        tracker.start(kind: .image)
        oldReporter(AnalysisProgressUpdate(stage: .responding))

        #expect(tracker.state?.stage == .preparing)
        #expect(tracker.state?.kind == .image)
    }

    @Test func aLateResultOfAnOlderRunIsIgnored() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        let oldRunID = tracker.runID

        tracker.start(kind: .image)
        let newRunID = tracker.runID

        #expect(tracker.finish(.complete, runID: oldRunID) == nil)
        #expect(tracker.state?.outcome == .running)
        #expect(tracker.finish(.failed, runID: newRunID)?.outcome == .failed)
    }

    @Test func aResetRunCannotBeFinishedAfterwards() {
        let tracker = AnalysisProgressTracker()
        tracker.start(kind: .text)
        let runID = tracker.runID

        tracker.reset()

        #expect(tracker.finish(.complete, runID: runID) == nil)
        #expect(tracker.state == nil)
    }

    @Test func theElapsedTimeUsesTheMonotonicClock() {
        var uptime: TimeInterval = 1_000
        let tracker = AnalysisProgressTracker { uptime }
        let state = tracker.start(kind: .text)

        #expect(state.elapsedSeconds(nowUptime: uptime) == 0)
        #expect(state.elapsedMilliseconds(nowUptime: uptime) == 0)
        uptime += 3
        #expect(state.elapsedSeconds(nowUptime: uptime) == 3)
        #expect(state.elapsedMilliseconds(nowUptime: uptime) == 3_000)
        #expect(tracker.elapsedSeconds == 3)

        // A wall-clock jump cannot move the uptime backwards; the guard still keeps
        // the displayed time non-negative.
        #expect(state.elapsedSeconds(nowUptime: uptime - 5) == 0)
    }

    // MARK: - Local duration history

    @Test func theEstimateNeedsEnoughLocalHistory() {
        #expect(AnalysisDurationEstimate.estimateSeconds(samples: []) == nil)
        #expect(AnalysisDurationEstimate.estimateSeconds(samples: [2_000, 3_000]) == nil)
        #expect(AnalysisDurationEstimate.estimateSeconds(samples: [2_000, 3_000, 4_000]) == 3...4)
    }

    @Test func theEstimateIgnoresASingleOutlier() throws {
        let estimate = AnalysisDurationEstimate.estimateSeconds(
            samples: [4_000, 4_200, 4_500, 4_600, 90_000]
        )
        let band = try #require(estimate)
        // The 90 s outlier must not widen the band into a useless range.
        #expect(band.upperBound <= 10)
        #expect(band.lowerBound >= 3)
    }

    @Test func theEstimateIsStoredPerProviderModelAndKind() {
        let textKey = AnalysisDurationEstimate.key(provider: .openai, model: "gpt-5.6-luna", kind: .text)
        let imageKey = AnalysisDurationEstimate.key(provider: .openai, model: "gpt-5.6-luna", kind: .image)
        let otherModelKey = AnalysisDurationEstimate.key(provider: .openai, model: "gpt-5.6-terra", kind: .text)
        #expect(textKey != imageKey)
        #expect(textKey != otherModelKey)
        #expect(textKey.contains("|"))
        #expect(textKey.hasSuffix("|text"))
    }

    @Test func theDurationHistoryNeedsThreeSamples() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .text) == nil)

        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 4_000)
        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 5_000)
        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .text) == nil)

        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 6_000)
        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .text) != nil)
    }

    @Test func theDurationHistoryIsKeptPerProviderModelAndKind() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        for _ in 0..<3 {
            store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 4_000)
        }

        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .image) == nil)
        #expect(store.store.estimate(provider: .openai, model: "m", kind: .text) == nil)
        #expect(store.store.estimate(provider: .gemini, model: "other", kind: .text) == nil)
        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .text) != nil)
    }

    @Test func theDurationHistorySurvivesAsJson() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 4_000)
        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 5_000)

        let data = try #require(store.defaults.data(forKey: AnalysisDurationStore.storageKey))
        let decoded = try JSONDecoder().decode([String: [Int]].self, from: data)
        let key = AnalysisDurationEstimate.key(provider: .gemini, model: "m", kind: .text)
        #expect(decoded[key] == [4_000, 5_000])

        // A second store on the same defaults reads the same history.
        let other = AnalysisDurationStore(defaults: store.defaults)
        #expect(other.samples(provider: .gemini, model: "m", kind: .text) == [4_000, 5_000])
    }

    @Test func aBrokenDurationHistoryIsIgnored() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        store.defaults.set(Data("not json".utf8), forKey: AnalysisDurationStore.storageKey)

        #expect(store.store.history().isEmpty)
        #expect(store.store.estimate(provider: .gemini, model: "m", kind: .text) == nil)
    }

    @Test func anOldPropertyListHistoryIsStillRead() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        let key = AnalysisDurationEstimate.key(provider: .gemini, model: "m", kind: .text)
        store.defaults.set([key: [7_000, 8_000, 9_000]], forKey: AnalysisDurationStore.storageKey)

        #expect(store.store.samples(provider: .gemini, model: "m", kind: .text) == [7_000, 8_000, 9_000])
        // The next write moves the history to JSON.
        store.store.record(provider: .gemini, model: "m", kind: .text, durationMillis: 9_500)
        #expect(store.defaults.data(forKey: AnalysisDurationStore.storageKey) != nil)
    }

    @Test func onlyTheLastSamplesAreKept() throws {
        let store = try makeStore()
        defer { store.defaults.removePersistentDomain(forName: store.name) }

        for _ in 0..<(AnalysisDurationEstimate.maxSamples + 4) {
            store.store.record(provider: .openai, model: "m", kind: .text, durationMillis: 1_000)
        }

        #expect(store.store.samples(provider: .openai, model: "m", kind: .text).count == AnalysisDurationEstimate.maxSamples)
    }

    // MARK: - Helpers

    /// A store on its own defaults suite, so the tests do not touch the app data.
    private func makeStore() throws -> (store: AnalysisDurationStore, defaults: UserDefaults, name: String) {
        let name = "AnalysisProgressTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        return (AnalysisDurationStore(defaults: defaults), defaults, name)
    }
}
