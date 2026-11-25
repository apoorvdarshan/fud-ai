import SwiftUI

extension EnvironmentValues {
    @Entry var voiceAssistantAnimationActive = false
}

struct VoiceMascotPose {
    let bob: Double
    let tilt: Double
    let blink: Double
    let mouth: Double
    let stretch: Double

    static func sample(mode: LiveOrbMode, time: Double, level: Double, animated: Bool, phase: Double = 0) -> Self {
        guard animated else { return Self(bob: 0, tilt: 0, blink: 0, mouth: 0, stretch: 0) }
        let level = level.isFinite ? min(max(level, 0), 1) : 0
        let time = time + phase
        let cycle = time.truncatingRemainder(dividingBy: 4.7)
        let blink = max(pulse(cycle, start: 4.16, duration: 0.28),
                        0.85 * pulse(cycle, start: 4.45, duration: 0.20))
        let sway = sin(time * 2.1)
        let breath = 0.016 * sway
        switch mode {
        case .idle:
            let beat = time.truncatingRemainder(dividingBy: 5.2)
            let hop = pulse(beat, start: 0.44, duration: 0.68)
            let squash = pulse(beat, start: 0.20, duration: 0.24) + pulse(beat, start: 1.07, duration: 0.26)
            return Self(bob: 0.012 * sway - 0.042 * hop, tilt: 1.8 * sway + 2.0 * hop,
                        blink: blink, mouth: 0, stretch: breath + 0.044 * hop - 0.046 * squash)
        case .listening:
            let nod = pulse(time.truncatingRemainder(dividingBy: 3.4), start: 0.3, duration: 0.55)
            return Self(bob: 0.008 * sway - 0.014 - 0.022 * level + 0.017 * nod,
                        tilt: -3.6 + sway - 2.1 * level + 2.4 * nod, blink: blink,
                        mouth: 0, stretch: breath + 0.025 * level - 0.021 * nod)
        case .thinking:
            let ponder = sin(time * 2.4)
            return Self(bob: -0.008 - 0.014 * ponder, tilt: 5.5 * ponder,
                        blink: blink, mouth: 0, stretch: -0.012 - breath)
        case .speaking:
            let mouth = max(0, (level - 0.16) / 0.84)
            return Self(bob: 0.009 * sway - 0.034 * mouth, tilt: (2.0 + mouth) * sway,
                        blink: blink, mouth: mouth, stretch: breath + 0.052 * mouth)
        }
    }

    private static func pulse(_ time: Double, start: Double, duration: Double) -> Double {
        let fraction = (time - start) / duration
        guard fraction > 0, fraction < 1 else { return 0 }
        let wave = sin(.pi * fraction)
        return wave * wave
    }
}

/// Transparent sprite with separately animated eyelids and an audio-driven mouth.
struct VoiceMascotView: View {
    var mode: LiveOrbMode
    var profile = VoiceMascotProfile()
    var level: () -> Double = { 0 }
    var compact = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.voiceAssistantAnimationActive) private var animationActive
    @State private var time = 0.0

    private var animated: Bool { !reduceMotion && animationActive }

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let mascot = profile.mascot
            let pose = VoiceMascotPose.sample(mode: mode, time: time, level: animated ? level() : 0,
                                              animated: animated,
                                              phase: Double(VoiceMascot.allCases.firstIndex(of: mascot) ?? 0) * 0.17)
            let color = profile.options.color.rgb.map { Color(hex: UInt($0)) }
                ZStack {
                    Image(mascot.asset).resizable()
                    if let color {
                        Image(mascot.asset + "Color").resizable().renderingMode(.template)
                            .foregroundStyle(color)
                    }
                    if pose.blink > 0 {
                        ZStack {
                            Image(mascot.asset + "Blink").resizable()
                            if let color, mascot != .brulee {
                                Image(mascot.asset + "BlinkColor").resizable().renderingMode(.template)
                                    .foregroundStyle(color)
                            }
                        }
                        .compositingGroup()
                        .opacity(pose.blink)
                    }
                    Canvas { canvas, size in
                        let side = size.width
                        let x = side * mascot.mouth.x
                        let y = side * mascot.mouth.y
                        let ink = Color(red: 0.23, green: 0.015, blue: 0.15)
                        let halfWidth = side * (0.031 + pose.mouth * 0.022)
                        if pose.mouth < 0.06 {
                            let smile = Path { path in
                                path.move(to: CGPoint(x: x - halfWidth, y: y))
                                path.addQuadCurve(to: CGPoint(x: x + halfWidth, y: y),
                                                  control: CGPoint(x: x, y: y + side * 0.022))
                            }
                            canvas.stroke(smile, with: .color(ink),
                                          style: StrokeStyle(lineWidth: side * 0.009, lineCap: .round))
                        } else {
                            let h = side * (0.012 + pose.mouth * 0.055)
                            let mouth = Path { path in
                                path.move(to: CGPoint(x: x - halfWidth, y: y))
                                path.addQuadCurve(to: CGPoint(x: x + halfWidth, y: y),
                                                  control: CGPoint(x: x, y: y - h * 0.68))
                                path.addQuadCurve(to: CGPoint(x: x - halfWidth, y: y),
                                                  control: CGPoint(x: x, y: y + h * 1.55))
                                path.closeSubpath()
                            }
                            canvas.fill(mouth, with: .color(ink))
                            if pose.mouth > 0.32 {
                                let tongue = CGRect(x: x - halfWidth * 0.49, y: y + h * 0.35,
                                                    width: halfWidth * 0.98, height: h * 0.30)
                                canvas.fill(Path(ellipseIn: tongue),
                                            with: .color(Color(red: 1, green: 0.35, blue: 0.55)))
                            }
                        }
                    }
                }
                .frame(width: side, height: side)
                .scaleEffect(x: 1 - 0.65 * pose.stretch, y: 1 + pose.stretch, anchor: .bottom)
                .rotationEffect(.degrees(pose.tilt * (compact ? 1.45 : 1)))
                .offset(y: side * pose.bob * (compact ? 1.3 : 1))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task(id: animated) {
            guard animated else { return }
            // The secondary, non-key window can suspend a SwiftUI animation timeline.
            // Tick only this view from a cancellable clock while its owner is active.
            let start = ProcessInfo.processInfo.systemUptime
            while !Task.isCancelled {
                time = ProcessInfo.processInfo.systemUptime - start
                do { try await Task.sleep(for: .milliseconds(compact && mode == .idle ? 60 : 33)) }
                catch { return }
            }
        }
        .accessibilityHidden(true)
    }
}
