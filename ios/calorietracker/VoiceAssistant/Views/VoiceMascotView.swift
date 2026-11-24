import SwiftUI

extension EnvironmentValues {
    @Entry var voiceAssistantAnimationActive = false
}

struct VoiceMascotPose {
    let bob: Double
    let tilt: Double
    let blink: Double
    let mouth: Double

    static func sample(mode: LiveOrbMode, time: Double, level: Double, animated: Bool) -> Self {
        guard animated else { return Self(bob: 0, tilt: 0, blink: 0, mouth: 0) }
        let level = level.isFinite ? min(max(level, 0), 1) : 0
        let cycle = time.truncatingRemainder(dividingBy: 4.7)
        let blink = max(0, 1 - abs(cycle - 4.3) / 0.105)
        let sway = sin(time * 2.1)
        switch mode {
        case .idle: return Self(bob: 0.014 * sway, tilt: sway, blink: blink, mouth: 0)
        case .listening:
            return Self(bob: 0.012 * sway - 0.022 * level, tilt: -2.5 * level + sway, blink: blink, mouth: 0)
        case .thinking:
            return Self(bob: 0.020 * sway, tilt: 3.0 * sin(time * 2.7), blink: blink, mouth: 0)
        case .speaking:
            let mouth = max(0, (level - 0.16) / 0.84)
            return Self(bob: 0.010 * sway - 0.020 * mouth, tilt: 1.7 * sway, blink: blink, mouth: mouth)
        }
    }
}

/// Transparent sprite with separately animated eyelids and an audio-driven mouth.
struct VoiceMascotView: View {
    var mode: LiveOrbMode
    var level: () -> Double = { 0 }
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.voiceAssistantAnimationActive) private var animationActive

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let animated = !reduceMotion && animationActive
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !animated)) { context in
                let pose = VoiceMascotPose.sample(mode: mode, time: context.date.timeIntervalSinceReferenceDate,
                                                  level: animated ? level() : 0, animated: animated)
                ZStack {
                    Image("VoiceMascot").resizable()
                    Image("VoiceMascotBlink").resizable().opacity(pose.blink)
                    Canvas { canvas, size in
                        let side = size.width
                        let x = side * 0.477
                        let y = side * 0.787
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
                .rotationEffect(.degrees(pose.tilt))
                .offset(y: side * pose.bob)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .accessibilityHidden(true)
    }
}
