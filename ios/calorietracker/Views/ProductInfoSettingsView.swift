import SwiftUI

/// Settings > App Preferences > Product Information (#427): turn off the barcode
/// product details block or choose which Open Food Facts fields it shows, and add
/// an optional USDA FoodData Central key for barcodes Open Food Facts can't use (#533).
struct ProductInfoSettingsView: View {
    @AppStorage(ProductInfoSettings.isShownKey) private var isShown = true
    @AppStorage(ProductInfoSettings.hiddenFieldsKey) private var hiddenFieldsRaw = ""
    @State private var usdaAPIKey = FoodDataCentralSettings.apiKey ?? ""
    @State private var showsUSDAAPIKey = false

    var body: some View {
        Form {
            Section {
                Toggle("Show Product Information", isOn: $isShown)
                    .tint(AppColors.calorie)
            } footer: {
                Text("Shown at the end of the review and edit screens for barcode-scanned foods. Allergen Check is not affected and always appears when you have allergen sensitivities set.")
            }

            if isShown {
                Section {
                    ForEach(ProductInfoField.allCases) { field in
                        Toggle(isOn: binding(for: field)) {
                            Text(field.title)
                        }
                        .tint(AppColors.calorie)
                    }
                } header: {
                    Text("Details to Show")
                }
            }

            usdaSection
        }
        .navigationTitle("Product Information")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Stored in the Keychain; with no key the app never contacts FoodData Central.
    private var usdaSection: some View {
        Section {
            HStack {
                Label {
                    Text("API Key")
                } icon: {
                    Image(systemName: "key.fill")
                        .foregroundStyle(AppColors.calorie)
                }
                Spacer()
                Group {
                    if showsUSDAAPIKey {
                        TextField("Optional", text: $usdaAPIKey)
                    } else {
                        SecureField("Optional", text: $usdaAPIKey)
                    }
                }
                .textFieldStyle(.plain)
                .multilineTextAlignment(.trailing)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onChange(of: usdaAPIKey) { _, newValue in
                    FoodDataCentralSettings.setAPIKey(newValue)
                }
                Button {
                    showsUSDAAPIKey.toggle()
                } label: {
                    Image(systemName: showsUSDAAPIKey ? "eye.fill" : "eye.slash.fill")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 14))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(showsUSDAAPIKey ? Text("Hide API Key") : Text("Show API Key"))
            }

            Link(destination: FoodDataCentralSettings.apiKeySignupURL) {
                Label("Get a Free API Key", systemImage: "arrow.up.right.square")
            }
            .tint(AppColors.calorie)
        } header: {
            Text("USDA FoodData Central")
        } footer: {
            Text("When Open Food Facts and your saved labels have no nutrition for a scanned barcode, Fud AI looks it up in USDA FoodData Central (mostly US products) with your own free key. Only the barcode is sent, and nothing is sent without a key.")
        }
    }

    private func binding(for field: ProductInfoField) -> Binding<Bool> {
        Binding(
            get: { !ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw).contains(field) },
            set: { isVisible in
                var hidden = ProductInfoSettings.hiddenFields(from: hiddenFieldsRaw)
                if isVisible {
                    hidden.remove(field)
                } else {
                    hidden.insert(field)
                }
                hiddenFieldsRaw = ProductInfoSettings.storageValue(for: hidden)
            }
        )
    }
}
