import RevenueCat
import SwiftUI

/// Settings → Voice Assistant: who the assistant talks through, its voice and
/// language, how it behaves, hosted minutes, and how to open it from the system.
struct VoiceAssistantSettingsView: View {
    private let settings = VoiceAssistantSettings()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var preview = VoicePreviewController.shared
    @State private var assistant = VoiceAssistantController.shared

    @State private var choice = VoiceAssistantSettings().providerChoice
    @State private var language = VoiceAssistantSettings().language
    @State private var autoCapture = VoiceAssistantSettings().autoCapture
    @State private var confirmSaves = VoiceAssistantSettings().confirmSaves
    @State private var saveToCoach = VoiceAssistantSettings().saveToCoach
    /// Bumped when a model or voice changes, so the pickers re-read settings.
    @State private var revision = 0
    @State private var rc = RevenueCatManager.shared
    @State private var voiceQuota: HostedVoiceQuota?
    @State private var quotaLoaded = false
    @State private var sheet: VoiceAssistantSheet?

    private var route: VoiceAssistantRoute {
        _ = revision
        _ = rc.activePlan
        return VoiceAssistantRouting.current(settings: settings)
    }

    /// The provider whose model and voice the pickers change.
    private var configuredProvider: LiveProvider? {
        if let fixed = choice.fixedProvider { return fixed }
        if case .ready(let provider) = route { return provider }
        return nil
    }

    var body: some View {
        List {
            providerSection
            if let provider = configuredProvider {
                modelAndVoiceSection(provider)
            }
            languageSection
            behaviorSection
            if rc.hasHostedEntitlement || choice == .hosted {
                minutesSection
            }
            shortcutsSection
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Voice Assistant")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .paywall: HostedPaywallView()
            case .credits: HostedCreditsSheet()
            }
        }
        .task {
            revision += 1
            await loadVoiceQuota()
        }
        .onDisappear { preview.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { preview.stop() }
        }
        .onChange(of: route) { _, _ in preview.stop() }
        .onChange(of: locale) { _, _ in preview.stop() }
        .onChange(of: assistant.isBusy) { _, busy in
            if busy { preview.stop() }
        }
        .onChange(of: preview.isRunning) { wasRunning, running in
            if wasRunning, !running, rc.hasHostedEntitlement {
                Task { await loadVoiceQuota() }
            }
        }
    }

    // MARK: Provider

    private var providerSection: some View {
        Section {
            Picker(selection: $choice) {
                ForEach(VoiceAssistantProviderChoice.allCases) { option in
                    Text(option.localizedName).tag(option)
                }
            } label: {
                Label {
                    Text("Provider")
                } icon: {
                    Image(systemName: "waveform.circle")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.voiceAssistant.provider")
            .onChange(of: choice) { _, newValue in
                preview.stop()
                settings.providerChoice = newValue
                revision += 1
                Task { await loadVoiceQuota() }
            }

            routeStatus
        } header: {
            Text("Provider")
        } footer: {
            Text("Automatic uses the provider selected in AI Providers when it's Gemini, OpenAI, xAI or Qwen and its key is saved; otherwise Fud AI with a Plus or Pro plan. Own keys are the ones saved in AI Providers.")
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    private var routeStatus: some View {
        switch route {
        case .ready(let provider):
            Label {
                Text(provider == .hosted
                    ? String(localized: "Uses Fud AI with your plan")
                    : String(localized: "Uses \(provider.displayName) with your API key"))
            } icon: {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AppColors.calorie)
            }
            .foregroundStyle(.secondary)
        case .needsAccess(let reason):
            switch reason {
            case .missingKey(let provider):
                Label {
                    Text("No \(provider.displayName) API key saved")
                } icon: {
                    Image(systemName: "key.slash")
                        .foregroundStyle(.orange)
                }
                .foregroundStyle(.secondary)
                addKeyLink
            case .needsSubscription:
                Label {
                    Text("Fud AI voice needs a Plus or Pro plan")
                } icon: {
                    Image(systemName: "lock.fill")
                        .foregroundStyle(.orange)
                }
                .foregroundStyle(.secondary)
                subscribeButton
            case .noProvider:
                Label {
                    Text("Subscribe to Plus or Pro, or add a Gemini, OpenAI, xAI or Qwen API key")
                } icon: {
                    Image(systemName: "exclamationmark.circle")
                        .foregroundStyle(.orange)
                }
                .foregroundStyle(.secondary)
                subscribeButton
                addKeyLink
            }
        }
    }

    private var addKeyLink: some View {
        NavigationLink(value: ProfileSettingsCategory.aiProviders) {
            Label {
                Text("Add an API Key in AI Providers")
            } icon: {
                Image(systemName: "key.horizontal")
                    .foregroundStyle(AppColors.calorie)
            }
        }
        .accessibilityIdentifier("settings.voiceAssistant.addKey")
    }

    private var subscribeButton: some View {
        Button {
            sheet = .paywall
        } label: {
            Label {
                Text("See Plus & Pro Plans")
            } icon: {
                Image(systemName: "sparkles")
                    .foregroundStyle(AppColors.calorie)
            }
        }
        .tint(.primary)
    }

    // MARK: Model and voice

    private func modelAndVoiceSection(_ provider: LiveProvider) -> some View {
        Section {
            if provider != .hosted {
                Picker(selection: Binding(
                    get: { _ = revision; return settings.model(for: provider) },
                    set: { preview.stop(); settings.setModel($0, for: provider); revision += 1 }
                )) {
                    ForEach(provider.models, id: \.self) { model in
                        Text(verbatim: model).tag(model)
                    }
                } label: {
                    Label {
                        Text("Model")
                    } icon: {
                        Image(systemName: "brain")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .accessibilityIdentifier("settings.voiceAssistant.model")
            }

            Picker(selection: Binding(
                get: { _ = revision; return settings.voice(for: provider) },
                set: { preview.stop(); settings.setVoice($0, for: provider); revision += 1 }
            )) {
                ForEach(provider.voices, id: \.self) { voice in
                    Text(verbatim: voice.prefix(1).uppercased() + voice.dropFirst()).tag(voice)
                }
            } label: {
                Label {
                    Text("Speaking Voice")
                } icon: {
                    Image(systemName: "speaker.wave.2")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .accessibilityIdentifier("settings.voiceAssistant.voice")

            Button {
                if preview.isRunning {
                    preview.stop()
                } else {
                    preview.start(
                        appLanguageCode: VoiceAssistantLanguage.appLanguageCode,
                        sample: String(localized: "Hi, I’m your Fud AI assistant. This is how my voice sounds.", locale: locale)
                    )
                }
            } label: {
                Label {
                    Text(preview.isRunning ? String(localized: "Stop Preview") : String(localized: "Preview Voice"))
                } icon: {
                    Image(systemName: preview.isRunning ? "stop.circle" : "play.circle")
                }
            }
            .disabled(!preview.isRunning && (route != .ready(provider) || assistant.isBusy))
            .accessibilityIdentifier("settings.voiceAssistant.preview")

            if preview.phase == .preparing {
                Text("Preparing preview…")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("settings.voiceAssistant.preview.preparing")
            }
            if let error = preview.errorMessage {
                Text(error)
                    .foregroundStyle(.red)
                    .accessibilityIdentifier("settings.voiceAssistant.preview.error")
            }
            if assistant.isBusy {
                Text("End your current voice conversation before previewing a voice.")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text(provider.localizedName)
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text(provider == .hosted
                    ? String(localized: "Fud AI chooses the model for Plus and Pro voice. Changes apply to the next conversation.")
                    : String(localized: "Changes apply to the next conversation."))
                Text("Plays a short sample without using your microphone. Uses API credits or Fud AI voice minutes.")
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    // MARK: Language

    private var languageSection: some View {
        Section {
            Picker(selection: $language) {
                Text("App Language").tag(VoiceAssistantLanguage.app)
                Text("Automatic").tag(VoiceAssistantLanguage.automatic)
                ForEach(VoiceAssistantLanguage.choices, id: \.self) { code in
                    Text(verbatim: VoiceAssistantLanguage.displayName(for: code)).tag(VoiceAssistantLanguage.specific(code))
                }
            } label: {
                Label {
                    Text("Response Language")
                } icon: {
                    Image(systemName: "globe")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .onChange(of: language) { _, newValue in
                preview.stop()
                settings.language = newValue
            }
        } footer: {
            Text("Automatic answers in the language you speak.")
        }
        .listRowBackground(AppColors.appCard)
    }

    // MARK: Behavior

    private var behaviorSection: some View {
        Section {
            toggleRow(
                title: "Auto-capture Photos",
                detail: "When the camera is open, the assistant takes the photo as soon as it sees the food. Gemini, Qwen and Fud AI see the camera continuously; OpenAI each time you speak; xAI and GPT-Live can't see it.",
                systemImage: "camera.viewfinder",
                isOn: $autoCapture,
                identifier: "settings.voiceAssistant.autoCapture"
            )
            .onChange(of: autoCapture) { _, newValue in preview.stop(); settings.autoCapture = newValue }

            toggleRow(
                title: "Confirm Before Saving",
                detail: "The assistant asks before it logs or changes anything. Deletes are always confirmed.",
                systemImage: "checkmark.shield",
                isOn: $confirmSaves,
                identifier: "settings.voiceAssistant.confirmSaves"
            )
            .onChange(of: confirmSaves) { _, newValue in preview.stop(); settings.confirmSaves = newValue }

            toggleRow(
                title: "Save Conversations to Coach",
                detail: "Adds what you and the assistant say to your Coach chat.",
                systemImage: "bubble.left.and.bubble.right",
                isOn: $saveToCoach,
                identifier: "settings.voiceAssistant.saveToCoach"
            )
            .onChange(of: saveToCoach) { _, newValue in preview.stop(); settings.saveToCoach = newValue }
        }
        .listRowBackground(AppColors.appCard)
    }

    private func toggleRow(title: LocalizedStringKey, detail: LocalizedStringKey, systemImage: String, isOn: Binding<Bool>, identifier: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Label {
                    Text(title)
                } icon: {
                    Image(systemName: systemImage)
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Toggle(title, isOn: isOn)
                    .labelsHidden()
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier(identifier)
            }
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.leading, 32)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: Hosted minutes

    private var minutesSection: some View {
        Section {
            if let voiceQuota {
                LabeledContent {
                    Text("\(voiceQuota.planSecondsLeft / 60) of \(voiceQuota.secondsLimit / 60) min")
                } label: {
                    Label {
                        Text("Plan Minutes Left")
                    } icon: {
                        Image(systemName: "timer")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                LabeledContent {
                    Text("\(voiceQuota.creditBank) (≈ \(voiceQuota.creditSeconds / 60) min)")
                } label: {
                    Label {
                        Text("Credits")
                    } icon: {
                        Image(systemName: "creditcard")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
            } else {
                Label {
                    Text(quotaLoaded ? String(localized: "Voice minutes aren't available right now.") : String(localized: "Loading…"))
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "timer")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            if rc.hasHostedEntitlement {
                Button {
                    sheet = .credits
                } label: {
                    Label {
                        Text("Buy Credits")
                    } icon: {
                        Image(systemName: "plus.circle")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .tint(.primary)
            }
        } header: {
            Text("Fud AI Voice")
        } footer: {
            if rc.trialEndsAt != nil {
                let minutes = rc.activePlan == .plus ? 2 : 5
                Text("Trial: 20 AI actions and \(minutes) voice minutes/day (UTC). Full plan limits after trial. Extra credits cost money.")
            } else {
                Text("Plus includes 20 minutes and Pro 100 minutes a month (UTC). After that, voice uses credits: 1 credit per 15 seconds.")
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    private func loadVoiceQuota() async {
        guard Purchases.isConfigured, rc.hasHostedEntitlement else {
            quotaLoaded = true
            return
        }
        voiceQuota = (try? await HostedAIService.fetchVoiceQuota()) ?? voiceQuota
        quotaLoaded = true
    }

    // MARK: System shortcuts

    private var shortcutsSection: some View {
        Section {
            shortcutRow(
                systemImage: "button.programmable",
                title: "Action Button",
                detail: "In the iOS Settings app, open Action Button, choose Shortcut, then Fud AI → Talk to Fud AI."
            )
            shortcutRow(
                systemImage: "mic.circle",
                title: "Siri",
                detail: "Say \"Hey Siri, talk to Fud AI\"."
            )
            shortcutRow(
                systemImage: "switch.2",
                title: "Control Center",
                detail: "Open Control Center, tap +, choose Add a Control, then Fud AI → Talk to Fud AI."
            )
        } header: {
            Text("Open It Faster")
        } footer: {
            Text("Each one opens Fud AI straight into a conversation.")
        }
        .listRowBackground(AppColors.appCard)
    }

    private func shortcutRow(systemImage: String, title: LocalizedStringKey, detail: LocalizedStringKey) -> some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        } icon: {
            Image(systemName: systemImage)
                .foregroundStyle(AppColors.calorie)
        }
        .accessibilityElement(children: .combine)
    }
}
