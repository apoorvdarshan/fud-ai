import Testing
@testable import calorietracker

struct FlashWidgetPaletteTests {
    @Test func defaultsAndLegacySnapshotsUseFlashButCustomAccentsStayIntact() {
        #expect(PinkNeoFlash.snapshotAccent(start: nil) == 0xFF006A)
        #expect(PinkNeoFlash.snapshotAccent(start: 0xFF375F) == 0xFF006A)
        #expect(PinkNeoFlash.snapshotAccent(start: 0xFFCC00) == 0xFFCC00)
    }

    @Test func shortcutGlyphAdaptsToAccentLuminance() {
        #expect(PinkNeoFlash.prefersDarkInk(on: 0xFFCC00))
        #expect(!PinkNeoFlash.prefersDarkInk(on: 0x5856D6))
    }
}
