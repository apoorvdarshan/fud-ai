import SwiftUI

struct VoiceMascotGallery: View {
    let preferences: VoiceMascotPreferences
    let select: (VoiceMascot) -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6),
                                  count: typeSize.isAccessibilitySize ? 2 : 4), spacing: 6) {
            ForEach(VoiceMascot.allCases) { mascot in
                let selected = preferences.selected == mascot
                let profile = preferences.profile(for: mascot)
                Button { select(mascot) } label: {
                    VStack(spacing: 2) {
                        VoiceMascotView(mode: .idle, profile: profile)
                            .frame(height: 55)
                        Text(verbatim: mascot.displayName)
                            .font(.caption2.weight(selected ? .bold : .medium))
                            .multilineTextAlignment(.center)
                            .lineLimit(2, reservesSpace: true)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.horizontal, 2)
                    .padding(.vertical, 6)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .foregroundStyle(.primary)
                    .background(selected ? AppColors.calorie.opacity(0.12) : AppColors.appBackground)
                    .overlay {
                        Rectangle().strokeBorder(selected ? AppColors.accentText : AppColors.flashRule.opacity(0.25),
                                                 lineWidth: selected ? 1.5 : 1)
                    }
                    .overlay(alignment: .topTrailing) {
                        if selected {
                            Image(systemName: "checkmark.circle.fill")
                                .font(.caption2.weight(.bold))
                                .foregroundStyle(AppColors.accentText)
                                .padding(4)
                                .accessibilityHidden(true)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: mascot.displayName))
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("settings.voiceMascot.gallery.\(mascot.rawValue)")
            }
        }
        .padding(.vertical, 4)
    }
}

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
