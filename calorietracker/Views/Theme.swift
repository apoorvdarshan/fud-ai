import SwiftUI

enum AppColors {
    // Calorie: Red → Pink
    static let calorieGradient: [Color] = [Color(hex: 0xFF375F), Color(hex: 0xFF6B8A)]
    static let calorie: Color = Color(hex: 0xFF375F)

    // Protein: Green → Mint
    static let proteinGradient: [Color] = [Color(hex: 0x30D158), Color(hex: 0x6EE7B7)]
    static let protein: Color = Color(hex: 0x30D158)

    // Carbs: Orange → Yellow
    static let carbsGradient: [Color] = [Color(hex: 0xFF9F0A), Color(hex: 0xFFD60A)]
    static let carbs: Color = Color(hex: 0xFF9F0A)

    // Fat: Blue → Cyan
    static let fatGradient: [Color] = [Color(hex: 0x0A84FF), Color(hex: 0x5AC8FA)]
    static let fat: Color = Color(hex: 0x0A84FF)

    // Background: warm cream in light, system dark in dark
    static let appBackground = Color("appBackground")
}

extension Color {
    init(hex: UInt, opacity: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
