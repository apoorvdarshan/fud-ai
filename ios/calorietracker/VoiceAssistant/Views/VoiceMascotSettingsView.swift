import SwiftUI

struct VoiceMascotSettingsView: View {
    @AppStorage(VoiceMascotPreferences.storageKey) private var stored = ""
    @State private var nameDraft = ""
    @FocusState private var editingName: Bool

    private var preferences: VoiceMascotPreferences { .decode(stored) }

    var body: some View {
        let profile = preferences.profile
        List {
            Section {
                VStack(spacing: 3) {
                    VoiceMascotView(mode: .idle, profile: profile)
                        .frame(width: 132, height: 132)
                    Text(verbatim: profile.name).font(.headline)
                }
                .frame(maxWidth: .infinity)
                FlashChoicePicker(
                    title: Text("Mascot", tableName: "VoiceMascot"),
                    selection: Binding(
                        get: { preferences.selected },
                        set: { selection in
                            saveName()
                            var updated = preferences
                            updated.selected = selection
                            stored = updated.encoded
                            nameDraft = updated.profile.options.name
                        }
                    ),
                    options: VoiceMascot.allCases
                ) { Text(verbatim: $0.displayName) }
                .accessibilityIdentifier("settings.voiceMascot.character")
                TextField("Name", text: $nameDraft, prompt: Text(verbatim: profile.mascot.displayName))
                    .focused($editingName)
                    .submitLabel(.done)
                    .onSubmit(saveName)
                    .onChange(of: nameDraft) { _, value in
                        if value.count > 100 { nameDraft = String(value.prefix(100)) }
                    }
                    .accessibilityIdentifier("settings.voiceMascot.name")
                if nameDraft != profile.options.name {
                    Button("Save", action: saveName)
                }
                FlashChoicePicker(
                    title: Text("Color", tableName: "VoiceMascot"),
                    selection: option(\.color),
                    options: VoiceMascotColor.allCases,
                    swatch: { $0.rgb.map { Color(hex: UInt($0)) } }
                ) { Text($0.title) }
                .accessibilityIdentifier("settings.voiceMascot.color")
                FlashChoicePicker(
                    title: Text("Personality", tableName: "VoiceMascot"),
                    selection: option(\.personality),
                    options: VoiceMascotPersonality.allCases
                ) { Text($0.title) }
                .accessibilityIdentifier("settings.voiceMascot.personality")
            } footer: {
                Text("Changes apply to the next conversation.", tableName: "VoiceMascot")
            }
            .flashListRows()
        }
        .flashEditorSurface()
        .navigationTitle(Text("Mascot", tableName: "VoiceMascot"))
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { nameDraft = preferences.profile.options.name }
    }

    private func option<Value>(_ path: WritableKeyPath<VoiceMascotOptions, Value>) -> Binding<Value> {
        Binding(
            get: { preferences.profile.options[keyPath: path] },
            set: { value in
                var updated = preferences
                updated.edit { $0[keyPath: path] = value }
                stored = updated.encoded
            }
        )
    }

    private func saveName() {
        var updated = preferences
        updated.edit { $0.name = nameDraft }
        stored = updated.encoded
        nameDraft = updated.profile.options.name
        editingName = false
    }
}
