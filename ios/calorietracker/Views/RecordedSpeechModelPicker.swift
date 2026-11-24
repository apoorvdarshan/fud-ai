import SwiftUI

struct RecordedSpeechModelPicker: View {
    let provider: SpeechProvider
    @AppStorage private var selectedModel: String

    init(provider: SpeechProvider) {
        self.provider = provider
        _selectedModel = AppStorage(wrappedValue: provider.defaultModel, SpeechSettings.recordedModelKey(for: provider))
    }

    var body: some View {
        Picker(selection: Binding(
            get: { provider.recordedModelOrDefault(selectedModel) },
            set: { selectedModel = provider.recordedModelOrDefault($0) }
        )) {
            ForEach(provider.recordedModelChoices, id: \.self) { model in
                Text(verbatim: model).tag(model)
            }
        } label: {
            Label("Transcription model", systemImage: "slider.horizontal.3")
        }
        .pickerStyle(.menu)
        .tint(.secondary)
    }
}
