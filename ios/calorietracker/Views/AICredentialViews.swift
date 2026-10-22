import SwiftUI

/// Displays the credential bound to one AI routing slot and lets the user pick a
/// different profile. The provider and the model of the route never change here.
struct AICredentialPickerSheet: View {
    let route: AIRoute
    let provider: AIProvider
    @Environment(\.dismiss) private var dismiss
    @State private var profiles: [AICredential] = []
    @State private var selectedID: String?
    @State private var isAdding = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Pick the API key profile this route uses. The provider and model stay the same.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    if profiles.isEmpty {
                        Text("No API key saved for this provider yet.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(profiles) { profile in
                        Button {
                            AICredentialStore.shared.bindRoute(route, credentialID: profile.id)
                            dismiss()
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: AICredentialLabel.text(for: profile, provider: profile.provider))
                                        .foregroundStyle(.primary)
                                    Text(verbatim: AICredentialStore.shared.maskedSecret(for: profile.id))
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if selectedID == profile.id {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                        }
                        .accessibilityLabel(
                            Text(verbatim: "\(AICredentialLabel.text(for: profile, provider: profile.provider)), \(AICredentialStore.shared.maskedSecret(for: profile.id))")
                        )
                    }
                } header: {
                    Text(verbatim: "Saved keys for \(provider.displayName)")
                }

                Section {
                    Button {
                        isAdding = true
                    } label: {
                        Label("Add a new key", systemImage: "plus")
                    }
                }
            }
            .navigationTitle("API key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $isAdding) {
                AICredentialEditorView(
                    mode: .create(provider: provider),
                    providerSelectionDisabled: true
                ) { created in
                    if let created {
                        AICredentialStore.shared.bindRoute(route, credentialID: created.id)
                    }
                    isAdding = false
                    refresh()
                }
            }
            .onAppear(perform: refresh)
        }
    }

    private func refresh() {
        profiles = AICredentialStore.shared.profiles(for: provider)
        selectedID = AICredentialStore.shared.routeProfile(route, provider: provider)?.id
    }
}

/// Add / rename / replace a single named key profile.
struct AICredentialEditorView: View {
    enum Mode {
        case create(provider: AIProvider)
        case edit(AICredential)
    }

    let mode: Mode
    /// A profile created from a route picker must stay on that route's provider.
    var providerSelectionDisabled: Bool = false
    var onSave: ((AICredential?) -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var label: String = ""
    @State private var secret: String = ""
    @State private var provider: AIProvider = .gemini
    @State private var errorMessage: String?
    @State private var isRevealing = false

    private var isEditing: Bool {
        if case .edit = mode { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            Form {
                if !isEditing && !providerSelectionDisabled {
                    Section {
                        Picker("Provider", selection: $provider) {
                            ForEach(AIProvider.allCases.filter { $0.requiresAPIKey }) { option in
                                Text(option.displayName).tag(option)
                            }
                        }
                    }
                } else {
                    Section {
                        LabeledContent("Provider", value: provider.displayName)
                    }
                }

                Section {
                    TextField("Name, e.g. OpenRouter – personal", text: $label)
                    HStack {
                        Group {
                            if isRevealing {
                                TextField("Paste the API key", text: $secret)
                            } else {
                                SecureField("Paste the API key", text: $secret)
                            }
                        }
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        Button {
                            isRevealing.toggle()
                        } label: {
                            Image(systemName: isRevealing ? "eye.slash" : "eye")
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(isRevealing ? "Hide API key" : "Show API key")
                    }
                } header: {
                    Text(isEditing ? "Key profile" : "New key profile")
                } footer: {
                    Text(isEditing
                         ? "Leave the key empty to keep the current one. The full key is never shown again."
                         : "Stored in this device's encrypted credential storage. Fud AI never shows the full key again.")
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(isEditing ? "Edit API key" : "Add API key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        switch mode {
        case .create(let provider):
            self.provider = provider
            label = ""
            secret = ""
        case .edit(let profile):
            provider = profile.provider
            label = profile.label ?? ""
            secret = ""
        }
    }

    private func save() {
        let trimmedLabel = label.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedSecret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        let store = AICredentialStore.shared
        switch mode {
        case .create:
            switch store.add(provider: provider, label: trimmedLabel, secret: trimmedSecret) {
            case .success(let credential):
                // The parent dismisses the sheet, so it is not dismissed twice.
                onSave?(credential)
            case .failure(let error):
                errorMessage = AICredentialLabel.errorText(for: error)
            }
        case .edit(let profile):
            // Name and key are one edit: a rejected key must not leave the profile
            // renamed without the new key.
            let newSecret = trimmedSecret.isEmpty ? nil : trimmedSecret
            switch store.update(id: profile.id, label: trimmedLabel, secret: newSecret) {
            case .success(let updated):
                onSave?(updated)
            case .failure(let error):
                errorMessage = AICredentialLabel.errorText(for: error)
            }
        }
    }
}

/// Lists every saved key profile so the user can rename, replace or delete them.
struct AICredentialManagerView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var profiles: [AICredential] = []
    @State private var editingProfile: AICredential?
    @State private var isAdding = false
    @State private var deletingProfile: AICredential?
    @State private var testingProfile: AICredential?

    var body: some View {
        List {
            Section {
                Text("Save more than one key for the same provider, give each a name, and choose which profile every AI route uses. Keys stay in encrypted storage and are shown only as a short fingerprint.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            ForEach(groupedProviders, id: \.self) { provider in
                Section(provider.displayName) {
                    let providerProfiles = profiles.filter { $0.provider == provider }
                    if providerProfiles.isEmpty {
                        Text("No key saved for this provider.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(providerProfiles) { profile in
                        Button {
                            editingProfile = profile
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: AICredentialLabel.text(for: profile, provider: profile.provider))
                                        .foregroundStyle(.primary)
                                    Text(verbatim: AICredentialStore.shared.maskedSecret(for: profile.id))
                                        .font(.caption.monospaced())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption)
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .swipeActions {
                            Button {
                                testingProfile = profile
                            } label: {
                                Label("Test", systemImage: "checkmark.circle")
                            }
                            .tint(.green)
                            Button(role: .destructive) {
                                deletingProfile = profile
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("API key profiles")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    isAdding = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add API key")
            }
        }
        .sheet(isPresented: $isAdding) {
            AICredentialEditorView(mode: .create(provider: .gemini)) { _ in
                isAdding = false
                refresh()
            }
        }
        .sheet(item: $editingProfile) { profile in
            AICredentialEditorView(mode: .edit(profile)) { _ in
                editingProfile = nil
                refresh()
            }
        }
        .sheet(item: $testingProfile) { profile in
            AICredentialTestSheet(profile: profile) {
                testingProfile = nil
                refresh()
            }
        }
        .confirmationDialog(
            "Delete API key?",
            isPresented: Binding(
                get: { deletingProfile != nil },
                set: { if !$0 { deletingProfile = nil } }
            ),
            titleVisibility: .visible,
            presenting: deletingProfile
        ) { profile in
            let routes = AICredentialLabel.routesUsing(profile, store: AICredentialStore.shared)
            let replacements = AICredentialStore.shared.profiles(for: profile.provider).filter { $0.id != profile.id }
            if !routes.isEmpty {
                ForEach(replacements) { replacement in
                    Button {
                        delete(profile, replacement: replacement)
                    } label: {
                        Text(verbatim: "Move those routes to \(AICredentialLabel.text(for: replacement, provider: replacement.provider))")
                    }
                }
            }
            Button("Delete and leave routes unconfigured", role: .destructive) {
                delete(profile, replacement: nil)
            }
            Button("Cancel", role: .cancel) { }
        } message: { profile in
            let routes = AICredentialLabel.routesUsing(profile, store: AICredentialStore.shared)
            if routes.isEmpty {
                Text("The key is removed from this device. No active route uses it.")
            } else {
                Text(verbatim: "The key is removed from this device. It is used by \(routes.map { AICredentialLabel.routeName($0) }.joined(separator: ", ")).")
            }
        }
        .onAppear(perform: refresh)
    }

    private var groupedProviders: [AIProvider] {
        let providers = Set(profiles.map(\.provider))
        return AIProvider.allCases.filter { providers.contains($0) }
    }

    private func refresh() {
        profiles = AICredentialStore.shared.all()
    }

    private func delete(_ profile: AICredential, replacement: AICredential?) {
        // The routes that use the profile are captured here, so the routes that only
        // used it as the provider default move to the replacement too.
        let providers = AICredentialLabel.routeProviders()
        _ = AICredentialStore.shared.delete(
            id: profile.id,
            replacementID: replacement?.id,
            routeProviders: providers
        )
        deletingProfile = nil
        refresh()
    }
}

/// Settings row that shows which key profile a route uses and opens the picker.
struct AICredentialRouteRow: View {
    let title: String
    let route: AIRoute
    let provider: AIProvider
    let onPick: () -> Void

    var body: some View {
        Button {
            onPick()
        } label: {
            HStack {
                Label {
                    Text(title)
                } icon: {
                    Image(systemName: "key.fill")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(verbatim: profile.map { AICredentialLabel.text(for: $0, provider: $0.provider) }
                         ?? String(localized: "No key selected"))
                        .foregroundStyle(profile == nil ? AppColors.calorie : .secondary)
                        .lineLimit(1)
                    if !fingerprint.isEmpty {
                        Text(fingerprint)
                            .font(.caption.monospaced())
                            .foregroundStyle(.tertiary)
                    }
                }
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: "\(title), \(profile.map { AICredentialLabel.text(for: $0, provider: $0.provider) } ?? String(localized: "No key selected"))"))
    }

    private var profile: AICredential? {
        AICredentialStore.shared.routeProfile(route, provider: provider)
    }

    private var fingerprint: String {
        profile.map { AICredentialStore.shared.maskedSecret(for: $0.id) } ?? ""
    }
}

/// Runs one minimal request against the provider of the chosen key and reports whether
/// the provider accepted it. The sheet states that the check uses one request from the
/// provider's account before the user starts it.
struct AICredentialTestSheet: View {
    let profile: AICredential
    var onFinish: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var isTesting = false
    @State private var hasRun = false
    @State private var failure: AIErrorKind?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(
                        "Fud AI sends one short request to \(profile.provider.displayName) to check that the key, the model and the endpoint work together. It uses one request from your provider account."
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                }

                if isTesting {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text("Checking the key…")
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if let failure {
                    Section {
                        Text(failure.message)
                            .foregroundStyle(.red)
                    }
                } else if hasRun, !isTesting {
                    Section {
                        Label("The provider accepted this key.", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                    }
                }
            }
            .navigationTitle("Test API key?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                        onFinish()
                    }
                    .disabled(isTesting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send test request") {
                        run()
                    }
                    .disabled(isTesting)
                }
            }
            .interactiveDismissDisabled(isTesting)
        }
    }

    private func run() {
        isTesting = true
        hasRun = false
        failure = nil
        Task {
            let model = profile.provider.defaultModel
            let baseURL = AIProviderSettings.customBaseURL(for: profile.provider)
                ?? profile.provider.baseURL
            let result = await ApiKeyTester.test(
                provider: profile.provider,
                model: model,
                apiKey: AICredentialStore.shared.secret(for: profile.id),
                baseURL: baseURL
            )
            failure = result.failure
            hasRun = true
            isTesting = false
        }
    }
}

/// One route of the routing overview: provider → key profile → model.
struct AIRoutingOverviewRow: View {
    let title: String
    let provider: AIProvider
    let model: String
    let profile: AICredential?
    let isActive: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(isActive ? .primary : .secondary)
                if !isActive {
                    Text("Off")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
            }
            Text(verbatim: "\(provider.displayName) → \(profile.map { AICredentialLabel.text(for: $0, provider: $0.provider) } ?? credentialLabel)")
                .font(.footnote)
                .foregroundStyle(profile == nil && isActive && needsKey ? AppColors.calorie : .secondary)
            if isActive && !model.isEmpty {
                Text(model)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }

    /// A local or keyless provider is fully configured without a key, so it must not
    /// look like a missing credential.
    private var needsKey: Bool { provider.requiresAPIKey }

    private var credentialLabel: String {
        needsKey ? String(localized: "No key selected") : String(localized: "No key needed")
    }
}

/// Shared labels so both platforms show the same wording for a profile.
enum AICredentialLabel {
    static func text(for profile: AICredential, provider: AIProvider) -> String {
        if let label = profile.label, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return label
        }
        return String(format: String(localized: "%@ key"), provider.displayName)
    }

    static func routeName(_ route: AIRoute) -> String {
        switch route {
        case .primary: return String(localized: "Primary AI")
        case .text: return String(localized: "Text AI")
        case .imageFallback: return String(localized: "Image fallback")
        case .textFallback: return String(localized: "Text fallback")
        }
    }

    static func errorText(for error: AICredentialError) -> String {
        switch error {
        case .labelRequired: return String(localized: "Enter a name for this key.")
        case .labelTooLong: return String(localized: "Keep the name under 60 characters.")
        case .keyRequired: return String(localized: "Enter an API key.")
        case .keyTooLong: return String(localized: "The key is too long.")
        case .keyContainsWhitespace: return String(localized: "The key contains spaces. Remove them and try again.")
        case .notFound: return String(localized: "This key no longer exists.")
        }
    }

    /// Slots that currently resolve to the given profile.
    /// The routes that are currently in use, with the provider each one runs on.
    static func routeProviders() -> [AIRoute: AIProvider] {
        var providers: [AIRoute: AIProvider] = [
            .primary: AIProviderSettings.selectedProvider,
        ]
        if AIProviderSettings.separateTextProviderEnabled {
            providers[.text] = AIProviderSettings.selectedTextProvider
        }
        if AIProviderSettings.fallbackEnabled {
            providers[.imageFallback] = AIProviderSettings.selectedFallbackProvider
        }
        if AIProviderSettings.textFallbackEnabled {
            providers[.textFallback] = AIProviderSettings.selectedTextFallbackProvider
        }
        return providers
    }

    /// The routes that actually use the profile. Routes that are switched off are not
    /// listed, so the delete confirmation only names routes that would really change.
    static func routesUsing(_ profile: AICredential, store: AICredentialStore) -> [AIRoute] {
        store.routesUsing(id: profile.id, routeProviders: routeProviders())
    }
}
