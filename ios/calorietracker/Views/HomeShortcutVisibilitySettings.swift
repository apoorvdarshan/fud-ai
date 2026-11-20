import SwiftUI

struct HomeShortcutVisibilitySettings: View {
    @AppStorage("hideVoiceShortcut") private var hideVoiceShortcut = false
    @AppStorage("hideBarcodePhoto") private var hideBarcodePhoto = false

    var body: some View {
        Toggle("Hide in-app voice shortcut", isOn: $hideVoiceShortcut)
        Toggle("Hide barcode product photos", isOn: $hideBarcodePhoto)
    }
}
