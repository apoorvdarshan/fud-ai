import Foundation

/// The 04 Flash palette shared by the app, widgets, and watchOS.
nonisolated enum PinkNeoFlash {
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
    static let mutedLight: UInt = 0x655D65
    static let mutedDark: UInt = 0xBDB4BD

    /// Snapshots from before Flash can still contain the old default gradient.
    static func snapshotAccent(start: UInt?) -> UInt {
        guard let start, start != 0xFF375F else { return pink }
        return start
    }

    static func prefersDarkInk(on hex: UInt) -> Bool {
        func linear(_ shift: Int) -> Double {
            let value = Double((hex >> shift) & 255) / 255
            return value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0) >= 0.179
    }
}
