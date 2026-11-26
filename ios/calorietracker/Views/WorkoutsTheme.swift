import SwiftUI
import UIKit

// MARK: - Workouts theme bridge
// The Workouts exercise library is ported from Delts (github.com/apoorvdarshan/delts).
// Delts styles its views through a small set of `delts*` palette tokens and view
// modifiers; this file re-implements that exact surface on top of Fud AI's theme
// (AppColors + the user-selectable AppThemeColor accent), so the ported views render
// with Fud AI's default look while keeping their code byte-for-byte close to Delts.

extension Color {
    /// Screen background — Fud AI's warm cream in light, near-black in dark.
    static var workoutBackground: Color { AppColors.appBackground }

    /// Card surface behind rows and hero imagery.
    static var workoutCard: Color { AppColors.appCard }

    /// Elevated panel behind menus / pills — one step off the card tone.
    static var workoutPanel: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.165, green: 0.165, blue: 0.180, alpha: 1)
                : UIColor(red: 0.937, green: 0.906, blue: 0.875, alpha: 1)
        })
    }

    /// Hairline strokes — matches Fud AI's divider tones.
    static var workoutHairline: Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.173, green: 0.173, blue: 0.180, alpha: 1)
                : UIColor(red: 0.780, green: 0.760, blue: 0.740, alpha: 1)
        })
    }

    /// Primary accent — the user's Fud AI theme color (default Fud Pink).
    static var workoutAccent: Color { AppColors.calorie }

    /// Softer companion accent — the gradient end of the theme color.
    static var workoutSecondaryAccent: Color {
        AppThemeColor.current.gradientColors.last ?? AppColors.calorie
    }

    /// Delts aliased "inferno" to its secondary accent; keep the alias.
    static var workoutInferno: Color { Color.workoutSecondaryAccent }

    /// Strong text.
    static var workoutCharcoal: Color { Color.primary }

    /// Muted/supporting text.
    static var workoutMutedText: Color { Color.secondary }

    /// Text/icons rendered on top of the accent color.
    static var workoutOnAccent: Color { Color.white }
}

struct WorkoutBackground: View {
    var body: some View {
        Color.workoutBackground
            .ignoresSafeArea()
    }
}

extension View {
    func workoutScreen() -> some View {
        background(WorkoutBackground())
            .scrollContentBackground(.hidden)
    }

    @ViewBuilder
    func workoutLiquidBarSurface(cornerRadius: CGFloat = 32) -> some View {
        modifier(WorkoutLiquidBarSurfaceModifier(cornerRadius: cornerRadius))
    }

    func workoutPressable() -> some View {
        buttonStyle(WorkoutPressableButtonStyle())
    }
}

private struct WorkoutLiquidBarSurfaceModifier: ViewModifier {
    let cornerRadius: CGFloat
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)

        if colorScheme == .light {
            content
                .background(Color.workoutPanel.opacity(0.72), in: shape)
                .overlay(shape.stroke(Color.workoutHairline.opacity(0.58), lineWidth: 0.6))
        } else if #available(iOS 26.0, *) {
            content
                .glassEffect(.regular.interactive(), in: shape)
        } else {
            content
                .background(Color.workoutPanel.opacity(0.62), in: shape)
                .overlay(shape.stroke(Color.workoutHairline.opacity(0.52), lineWidth: 0.5))
        }
    }
}

struct WorkoutPressableButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let isPressed = configuration.isPressed && isEnabled

        configuration.label
            .scaleEffect(isPressed ? 0.975 : 1)
            .opacity(isEnabled ? (isPressed ? 0.90 : 1) : 0.55)
            .animation(.easeOut(duration: 0.14), value: isPressed)
    }
}
