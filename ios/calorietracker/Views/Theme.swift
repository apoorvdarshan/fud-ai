import SwiftUI
import UIKit
import Observation

enum AppThemeColor: String, CaseIterable, Identifiable {
    case fudPink
    case red
    case orange
    case green
    case mint
    case teal
    case blue
    case purple
    case yellow
    case coral
    case roseGold
    case mochaBrown
    case indigo
    case lavender
    case skyCyan
    case graphite
    case babyPink
    case lime

    static let storageKey = "appThemeColor"
    static let defaultColor: AppThemeColor = .fudPink

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .fudPink: return LocalizedDisplayText.text("Fud Pink", polish: "Róż Fud")
        case .red: return LocalizedDisplayText.text("Red", polish: "Czerwony")
        case .orange: return LocalizedDisplayText.text("Orange", polish: "Pomarańczowy")
        case .green: return LocalizedDisplayText.text("Green", polish: "Zielony")
        case .mint: return LocalizedDisplayText.text("Mint", polish: "Miętowy")
        case .teal: return LocalizedDisplayText.text("Teal", polish: "Turkusowy")
        case .blue: return LocalizedDisplayText.text("Blue", polish: "Niebieski")
        case .purple: return LocalizedDisplayText.text("Purple", polish: "Fioletowy")
        case .yellow: return LocalizedDisplayText.text("Yellow", polish: "Żółty")
        case .coral: return LocalizedDisplayText.text("Coral", polish: "Koralowy")
        case .roseGold: return LocalizedDisplayText.text("Rose Gold", polish: "Różowe złoto")
        case .mochaBrown: return LocalizedDisplayText.text("Mocha Brown", polish: "Brąz mokka")
        case .indigo: return LocalizedDisplayText.text("Indigo", polish: "Indygo")
        case .lavender: return LocalizedDisplayText.text("Lavender", polish: "Lawendowy")
        case .skyCyan: return LocalizedDisplayText.text("Sky Cyan", polish: "Błękit")
        case .graphite: return LocalizedDisplayText.text("Graphite", polish: "Grafitowy")
        case .babyPink: return LocalizedDisplayText.text("Baby Pink", polish: "Pastelowy róż")
        case .lime: return LocalizedDisplayText.text("Lime", polish: "Limonkowy")
        }
    }

    var color: Color {
        Color(hex: startHex)
    }

    var gradientColors: [Color] {
        [Color(hex: startHex), Color(hex: endHex)]
    }

    var alternateIconName: String? {
        switch self {
        case .fudPink: return nil
        case .red: return "AppIconRed"
        case .orange: return "AppIconOrange"
        case .green: return "AppIconGreen"
        case .mint: return "AppIconMint"
        case .teal: return "AppIconTeal"
        case .blue: return "AppIconBlue"
        case .purple: return "AppIconPurple"
        case .yellow: return "AppIconYellow"
        case .coral: return "AppIconCoral"
        case .roseGold: return "AppIconRoseGold"
        case .mochaBrown: return "AppIconMochaBrown"
        case .indigo: return "AppIconIndigo"
        case .lavender: return "AppIconLavender"
        case .skyCyan: return "AppIconSkyCyan"
        case .graphite: return "AppIconGraphite"
        case .babyPink: return "AppIconBabyPink"
        case .lime: return "AppIconLime"
        }
    }

    static var current: AppThemeColor {
        guard let rawValue = UserDefaults.standard.string(forKey: storageKey),
              let themeColor = AppThemeColor(rawValue: rawValue) else {
            return defaultColor
        }
        return themeColor
    }

    static func color(for rawValue: String) -> AppThemeColor {
        AppThemeColor(rawValue: rawValue) ?? defaultColor
    }

    // Menu (dropdown) rows render SwiftUI colors as monochrome templates, so the
    // swatch has to be a pre-rendered UIImage marked alwaysOriginal to keep its color.
    var menuSwatchImage: UIImage {
        let size = CGSize(width: 22, height: 22)
        let image = UIGraphicsImageRenderer(size: size).image { context in
            let rect = CGRect(origin: .zero, size: size)
            UIBezierPath(ovalIn: rect).addClip()
            let colors = [UIColor(color), UIColor(gradientColors.last ?? color)].map(\.cgColor)
            if let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: colors as CFArray,
                locations: [0, 1]
            ) {
                context.cgContext.drawLinearGradient(
                    gradient,
                    start: .zero,
                    end: CGPoint(x: size.width, y: size.height),
                    options: []
                )
            }
        }
        return image.withRenderingMode(.alwaysOriginal)
    }

    @MainActor
    static func applyAppIconIfNeeded(for themeColor: AppThemeColor) {
        let application = UIApplication.shared
        guard application.supportsAlternateIcons,
              application.alternateIconName != themeColor.alternateIconName else {
            return
        }

        application.setAlternateIconName(themeColor.alternateIconName)
    }

    // Internal (not private) so WidgetSnapshotWriter can ship the raw hexes to
    // the Watch, which has no AppThemeColor and rebuilds Colors from the values.
    var startHex: UInt {
        switch self {
        case .fudPink: return PinkNeoFlash.pink
        case .red: return 0xFF3B30
        case .orange: return 0xFF9500
        case .green: return 0x34C759
        case .mint: return 0x00C7BE
        case .teal: return 0x30B0C7
        case .blue: return 0x0A84FF
        case .purple: return 0xAF52DE
        case .yellow: return 0xFFCC00
        case .coral: return 0xFF7F50
        case .roseGold: return 0xC9807C
        case .mochaBrown: return 0xA2845E
        case .indigo: return 0x5856D6
        case .lavender: return 0xB57EDC
        case .skyCyan: return 0x32ADE6
        case .graphite: return 0x8E8E93
        case .babyPink: return 0xFF8FAB
        case .lime: return 0xA0D911
        }
    }

    var endHex: UInt {
        switch self {
        case .fudPink: return PinkNeoFlash.pink
        case .red: return 0xFF6961
        case .orange: return 0xFFB340
        case .green: return 0x62D46F
        case .mint: return 0x66D4CF
        case .teal: return 0x64D2FF
        case .blue: return 0x5EAEFF
        case .purple: return 0xBF5AF2
        case .yellow: return 0xFFD60A
        case .coral: return 0xFFA382
        case .roseGold: return 0xE8B4B0
        case .mochaBrown: return 0xC9A57E
        case .indigo: return 0x7D7AFF
        case .lavender: return 0xD0A9F5
        case .skyCyan: return 0x70CFFF
        case .graphite: return 0xB8B8BE
        case .babyPink: return 0xFFB3C6
        case .lime: return 0xC3E956
        }
    }
}

@Observable
final class AppAppearancePalette {
    static let shared = AppAppearancePalette()
    var usesGray = UserDefaults.standard.string(forKey: "appearanceMode") == "darkGray"
}

enum AppColors {
    // The duplicate endpoint keeps existing chart/shape APIs on a solid fill.
    static var calorieGradient: [Color] { [calorie, calorie] }
    static var calorie: Color { AppThemeColor.current.color }
    static var onAccent: Color { contrastInk(on: AppThemeColor.current.startHex) }
    static var accentText: Color { AppThemeColor.current == .fudPink ? flashAccentText : calorie }
    static let flashRule = dynamicColor(light: PinkNeoFlash.inkLight, dark: PinkNeoFlash.inkDark)
    private static let flashAccentText = dynamicColor(light: 0xC50051, dark: 0xFF4D91)
    /// Inset information areas are neutral; pink is for actions and meaningful values.
    static var flashInset: Color {
        if AppAppearancePalette.shared.usesGray { return Color(hex: 0x26272B) }
        return AppThemeColor.current == .fudPink ? pinkNeoInset : appBackground
    }

    // Protein
    static var proteinGradient: [Color] { calorieGradient }
    static var protein: Color { calorie }

    // Carbs
    static var carbsGradient: [Color] { calorieGradient }
    static var carbs: Color { calorie }

    // Fat
    static var fatGradient: [Color] { calorieGradient }
    static var fat: Color { calorie }

    // Preserve the user's Dark gray and alternate accents; Fud Pink gets its own paper/ink surfaces.
    static var appBackground: Color {
        if AppAppearancePalette.shared.usesGray { return Color(hex: 0x202124) }
        return AppThemeColor.current == .fudPink ? pinkNeoPaper : Color("appBackground")
    }
    static var appCard: Color {
        if AppAppearancePalette.shared.usesGray { return Color(hex: 0x303136) }
        return AppThemeColor.current == .fudPink ? pinkNeoPanel : Color("appCard")
    }

    private static let pinkNeoPaper = dynamicColor(light: PinkNeoFlash.paperLight, dark: PinkNeoFlash.paperDark)
    private static let pinkNeoPanel = dynamicColor(light: PinkNeoFlash.panelLight, dark: PinkNeoFlash.panelDark)
    private static let pinkNeoInset = dynamicColor(light: PinkNeoFlash.insetLight, dark: PinkNeoFlash.insetDark)

    private static func dynamicColor(light: UInt, dark: UInt) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 255) / 255,
                           green: CGFloat((hex >> 8) & 255) / 255,
                           blue: CGFloat(hex & 255) / 255, alpha: 1)
        })
    }

    static func contrastInk(on rgb: UInt) -> Color {
        func linear(_ shift: UInt) -> Double {
            let value = Double((rgb >> shift) & 255) / 255
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        let luminance = 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        return luminance < 0.179 ? .white : .black
    }
}

/// The selected 04 Flash palette. Shared by the shell and its Home components.
enum PinkNeoFlash {
    static let paperLight: UInt = 0xFFFDFC
    static let paperDark: UInt = 0x121214
    static let panelLight: UInt = 0xFFFFFF
    static let panelDark: UInt = 0x19191B
    static let railDark: UInt = 0x18181A
    static let insetLight: UInt = 0xF6F4F3
    static let insetDark: UInt = 0x141416
    static let inkLight: UInt = 0x171219
    static let inkDark: UInt = 0xF9F5F7
    static let pink: UInt = 0xFF006A
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
