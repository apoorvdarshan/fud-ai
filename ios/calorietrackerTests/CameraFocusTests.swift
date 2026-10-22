import AVFoundation
import CoreGraphics
import Testing
@testable import calorietracker

struct CameraFocusTests {
    // MARK: - Focus state

    @Test func startsInContinuousAutofocus() {
        let state = CameraFocusState()
        #expect(state.mode == .continuous)
        #expect(!state.isUserFocused)
    }

    @Test func tapFocusesAndExposesOnceAndWatchesTheScene() {
        var state = CameraFocusState()
        let request = state.userFocus(at: CGPoint(x: 0.25, y: 0.75))
        #expect(request.point == CGPoint(x: 0.25, y: 0.75))
        #expect(request.focusMode == .autoFocus)
        #expect(request.exposureMode == .autoExpose)
        #expect(request.monitorsSubjectArea)
        #expect(state.mode == .held(CGPoint(x: 0.25, y: 0.75)))
        #expect(state.isUserFocused)
    }

    @Test func tapPointsAreClampedToTheFrame() {
        var state = CameraFocusState()
        #expect(state.userFocus(at: CGPoint(x: -0.2, y: 1.4)).point == CGPoint(x: 0, y: 1))
        #expect(state.userFocus(at: CGPoint(x: CGFloat.nan, y: .infinity)).point == CGPoint(x: 0.5, y: 0.5))
    }

    @Test func sceneChangeReturnsToContinuousAutofocusAtTheCentre() {
        var state = CameraFocusState()
        _ = state.userFocus(at: CGPoint(x: 0.1, y: 0.1))
        let request = state.sceneChanged()
        #expect(request == .continuous)
        #expect(request?.point == CGPoint(x: 0.5, y: 0.5))
        #expect(request?.focusMode == .continuousAutoFocus)
        #expect(request?.exposureMode == .continuousAutoExposure)
        #expect(request?.monitorsSubjectArea == false)
        #expect(state.mode == .continuous)
        // Nothing left to undo.
        #expect(state.sceneChanged() == nil)
    }

    @Test func holdTimeoutKeepsFocusingContinuouslyAtTheTappedPoint() {
        var state = CameraFocusState()
        _ = state.userFocus(at: CGPoint(x: 0.3, y: 0.6))
        let request = state.holdTimedOut(generation: state.generation)
        #expect(request?.point == CGPoint(x: 0.3, y: 0.6))
        #expect(request?.focusMode == .continuousAutoFocus)
        #expect(request?.exposureMode == .continuousAutoExposure)
        // A later scene change still hands focus back to the centre.
        #expect(request?.monitorsSubjectArea == true)
        #expect(state.mode == .following(CGPoint(x: 0.3, y: 0.6)))
        #expect(state.holdTimedOut(generation: state.generation) == nil)
        #expect(state.sceneChanged() == .continuous)
    }

    @Test func timeoutFromAnOlderTapKeepsTheNewerTap() {
        var state = CameraFocusState()
        _ = state.userFocus(at: CGPoint(x: 0.2, y: 0.2))
        let firstTap = state.generation
        _ = state.userFocus(at: CGPoint(x: 0.8, y: 0.8))
        #expect(state.holdTimedOut(generation: firstTap) == nil)
        #expect(state.mode == .held(CGPoint(x: 0.8, y: 0.8)))
        #expect(state.holdTimedOut(generation: state.generation) == .follow(CGPoint(x: 0.8, y: 0.8)))
    }

    @Test func timeoutAfterASceneChangeDoesNothing() {
        var state = CameraFocusState()
        _ = state.userFocus(at: CGPoint(x: 0.2, y: 0.2))
        let tap = state.generation
        _ = state.sceneChanged()
        #expect(state.holdTimedOut(generation: tap) == nil)
        #expect(state.mode == .continuous)
    }

    @Test func resetForgetsTheTapAndItsTimeout() {
        var state = CameraFocusState()
        _ = state.userFocus(at: CGPoint(x: 0.3, y: 0.3))
        let tap = state.generation
        state.reset()
        #expect(state.mode == .continuous)
        #expect(state.holdTimedOut(generation: tap) == nil)
        #expect(state.sceneChanged() == nil)
    }

    @Test func focusAndExposureModesFallBackWhenADeviceLacksOne() {
        let tap = CameraFocusRequest.hold(at: CameraFocusState.center)
        #expect(tap.focusMode(supported: { _ in true }) == .autoFocus)
        #expect(tap.focusMode(supported: { $0 == .continuousAutoFocus }) == .continuousAutoFocus)
        #expect(tap.focusMode(supported: { _ in false }) == nil)
        #expect(CameraFocusRequest.continuous.focusMode(supported: { $0 == .autoFocus }) == .autoFocus)
        #expect(tap.exposureMode(supported: { $0 == .continuousAutoExposure }) == .continuousAutoExposure)
        #expect(CameraFocusRequest.continuous.exposureMode(supported: { _ in true }) == .continuousAutoExposure)
        #expect(CameraFocusRequest.continuous.exposureMode(supported: { _ in false }) == nil)
    }

    // MARK: - AR tap conversion

    /// ARKit's portrait display transform when the view has the image's aspect ratio:
    /// image (x, y) → view (1 − y, x).
    private let portrait = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 1, ty: 0)

    private func isClose(_ point: CGPoint?, _ x: CGFloat, _ y: CGFloat) -> Bool {
        guard let point else { return false }
        return abs(point.x - x) < 1e-9 && abs(point.y - y) < 1e-9
    }

    @Test func portraitARTapMapsBackToSensorCoordinates() {
        let size = CGSize(width: 300, height: 400)
        // View (0.25, 0.1) is image (0.1, 0.75): the classic portrait (y, 1 − x).
        let tap = CameraFocusGeometry.devicePoint(forViewPoint: CGPoint(x: 75, y: 40), viewSize: size, displayTransform: portrait)
        #expect(isClose(tap, 0.1, 0.75))
        let center = CameraFocusGeometry.devicePoint(forViewPoint: CGPoint(x: 150, y: 200), viewSize: size, displayTransform: portrait)
        #expect(isClose(center, 0.5, 0.5))
    }

    @Test func aspectFillCroppingIsUndone() {
        // The view shows the middle 80% of the image's height: view y = 1.25 × image y − 0.125.
        let transform = CGAffineTransform(a: 1, b: 0, c: 0, d: 1.25, tx: 0, ty: -0.125)
        let tap = CameraFocusGeometry.devicePoint(forViewPoint: CGPoint(x: 50, y: 0), viewSize: CGSize(width: 100, height: 100), displayTransform: transform)
        #expect(isClose(tap, 0.5, 0.1))
    }

    @Test func degenerateARInputsGiveNoPoint() {
        #expect(CameraFocusGeometry.devicePoint(forViewPoint: .zero, viewSize: .zero, displayTransform: portrait) == nil)
        let singular = CGAffineTransform(a: 0, b: 0, c: 0, d: 0, tx: 0.5, ty: 0.5)
        #expect(CameraFocusGeometry.devicePoint(forViewPoint: CGPoint(x: 10, y: 10), viewSize: CGSize(width: 100, height: 100), displayTransform: singular) == nil)
    }

    @Test func arTapsOutsideTheImageAreClamped() {
        let tap = CameraFocusGeometry.devicePoint(forViewPoint: CGPoint(x: 400, y: -50), viewSize: CGSize(width: 300, height: 400), displayTransform: portrait)
        #expect(isClose(tap, 0, 0))
    }

    // MARK: - Close-up hand-off

    @Test func noHandOffWithoutACloserFocusingCamera() {
        #expect(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 150) == nil)
        #expect(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: -1) == nil)
        #expect(CloseUpFocusDetector(arMinimumFocusDistanceMM: -1, alternativeMinimumFocusDistanceMM: 20) == nil)
        #expect(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 20) != nil)
    }

    // `update` is mutating, so each sequence is collected before it is checked.

    @Test func lensAtItsNearLimitHandsOffOnceSustainedAfterWarmUp() throws {
        var detector = try #require(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 20))
        let handOffs = [
            // A lens parked at its near end while the camera starts doesn't count.
            detector.update(now: 0, lensPosition: 0, subjectDistanceMM: nil),
            detector.update(now: 0.3, lensPosition: 0, subjectDistanceMM: nil),
            detector.update(now: 0.6, lensPosition: 0.01, subjectDistanceMM: nil),
            detector.update(now: 0.9, lensPosition: 0.02, subjectDistanceMM: nil),
            detector.update(now: 1.2, lensPosition: 0, subjectDistanceMM: nil),
        ]
        #expect(handOffs == [false, false, false, false, true])
    }

    @Test func lensMovingAwayRestartsTheWait() throws {
        var detector = try #require(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 20))
        let handOffs = [
            detector.update(now: 0, lensPosition: 0.5, subjectDistanceMM: nil),
            detector.update(now: 1.0, lensPosition: 0.02, subjectDistanceMM: nil),
            // Autofocus found the subject again.
            detector.update(now: 1.3, lensPosition: 0.3, subjectDistanceMM: nil),
            detector.update(now: 1.4, lensPosition: 0.02, subjectDistanceMM: nil),
            detector.update(now: 1.8, lensPosition: 0.02, subjectDistanceMM: nil),
            detector.update(now: 2.0, lensPosition: 0.02, subjectDistanceMM: nil),
        ]
        #expect(handOffs == [false, false, false, false, false, true])
    }

    @Test func surfaceMeasuredInsideTheMinimumFocusDistanceHandsOff() throws {
        var detector = try #require(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 20))
        let handOffs = [
            detector.update(now: 0, lensPosition: 0.4, subjectDistanceMM: 300),
            detector.update(now: 1.0, lensPosition: 0.4, subjectDistanceMM: 120),
            detector.update(now: 1.5, lensPosition: 0.4, subjectDistanceMM: 110),
        ]
        #expect(handOffs == [false, false, true])
    }

    @Test func normalPhotoDistancesNeverHandOff() throws {
        var detector = try #require(CloseUpFocusDetector(arMinimumFocusDistanceMM: 150, alternativeMinimumFocusDistanceMM: 20))
        var handOffs: [Bool] = []
        for step in 0..<40 {
            handOffs.append(detector.update(now: Double(step) * 0.15, lensPosition: 0.35, subjectDistanceMM: 320))
        }
        handOffs.append(detector.update(now: 7, lensPosition: .nan, subjectDistanceMM: .nan))
        handOffs.append(detector.update(now: 8, lensPosition: 0.35, subjectDistanceMM: 0))
        #expect(!handOffs.contains(true))
    }
}
