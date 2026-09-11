import SwiftUI

struct OutdoorActivityDurationSheet: View {
    enum ActivityKind: Identifiable {
        case walking
        case running

        var id: Self { self }

        var title: String {
            switch self {
            case .walking: return "Walking"
            case .running: return "Running"
            }
        }

        var systemImage: String {
            switch self {
            case .walking: return "figure.walk"
            case .running: return "figure.run"
            }
        }
    }

    let activity: ActivityKind
    let onLog: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var customMinutes = ""
    @State private var isSubmitting = false
    @FocusState private var customFocused: Bool

    private var selectedMinutes: Int? {
        Int(customMinutes.filter(\.isNumber)).flatMap { (1...600).contains($0) ? $0 : nil }
    }

    private func submit(_ minutes: Int) {
        guard !isSubmitting else { return }
        isSubmitting = true
        onLog(minutes)
        dismiss()
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("How long?")
                    .font(.system(.title3, design: .rounded, weight: .semibold))

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(OutdoorActivitySettings.durationPresets, id: \.self) { minutes in
                        Button {
                            submit(minutes)
                        } label: {
                            Text("\(minutes) min")
                                .font(.system(.headline, design: .rounded, weight: .semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .foregroundStyle(AppColors.calorie)
                                .background(AppColors.calorie.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                        .disabled(isSubmitting)
                    }
                }

                HStack {
                    TextField("Custom minutes", text: $customMinutes)
                        .keyboardType(.numberPad)
                        .focused($customFocused)
                        .onChange(of: customMinutes) { _, value in
                            customMinutes = String(value.filter(\.isNumber).prefix(3))
                        }
                    Text("min")
                        .foregroundStyle(.secondary)
                }
                .padding()
                .background(AppColors.appCard)
                .clipShape(RoundedRectangle(cornerRadius: 14))

                Button {
                    guard let selectedMinutes else { return }
                    submit(selectedMinutes)
                } label: {
                    Label("Log \(activity.title)", systemImage: activity.systemImage)
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 14))
                }
                .disabled(selectedMinutes == nil || isSubmitting)

                Spacer()
            }
            .padding(20)
            .background(AppColors.appBackground)
            .navigationTitle(activity.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
