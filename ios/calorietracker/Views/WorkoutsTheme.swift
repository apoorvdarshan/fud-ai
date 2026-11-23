import SwiftUI
import UIKit

// MARK: - Workouts theme bridge
// The Workouts exercise library is ported from Delts (github.com/apoorvdarshan/delts).
// Delts styles its views through a small set of `delts*` palette tokens and view
// modifiers; this file re-implements that exact surface on top of Fud AI's theme
// (AppColors + the user-selectable AppThemeColor accent), so the ported views render
// with Fud AI's default look while keeping their code byte-for-byte close to Delts.

extension Color {
    /// Screen background following the app's appearance.
    static var workoutBackground: Color { AppColors.appBackground }

    /// Card surface behind rows and hero imagery.
    static var workoutCard: Color { AppColors.appCard }

    /// Elevated panel behind menus / pills — one step off the card tone.
    static var workoutPanel: Color { AppColors.appCard }

    /// Hairline strokes — matches Fud AI's divider tones.
    static var workoutHairline: Color { AppColors.flashRule }

    /// Primary accent — the user's Fud AI theme color (default Fud Pink).
    static var workoutAccent: Color { AppColors.calorie }

    static var workoutSecondaryAccent: Color { AppColors.calorie }

    /// Delts aliased "inferno" to its secondary accent; keep the alias.
    static var workoutInferno: Color { Color.workoutSecondaryAccent }

    /// Strong text.
    static var workoutCharcoal: Color { Color.primary }

    /// Muted/supporting text.
    static var workoutMutedText: Color { Color.secondary }

    /// Text/icons rendered on top of the accent color.
    static var workoutOnAccent: Color { AppColors.onAccent }
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

    func workoutLiquidBarSurface(cornerRadius: CGFloat = 32) -> some View {
        flashPanel()
    }

    func workoutPressable() -> some View {
        buttonStyle(WorkoutPressableButtonStyle())
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
