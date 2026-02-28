import SwiftUI
import Combine

struct TextFoodInputView: View {
    @State private var foodDescription = ""
    @State private var placeholderIndex = 0
    @FocusState private var isFocused: Bool
    @Environment(\.dismiss) private var dismiss

    var onSubmit: (String) -> Void

    private let placeholders = [
        "2 eggs, toast with butter and a coffee",
        "Chipotle burrito bowl with chicken and rice",
        "Domino's pepperoni pizza, 2 slices",
        "Greek yogurt with granola and blueberries",
    ]

    private let timer = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                ZStack(alignment: .topLeading) {
                    if foodDescription.isEmpty {
                        Text(placeholders[placeholderIndex])
                            .foregroundStyle(.tertiary)
                            .font(.body)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 10)
                            .transition(.asymmetric(
                                insertion: .move(edge: .bottom).combined(with: .opacity),
                                removal: .move(edge: .top).combined(with: .opacity)
                            ))
                            .id(placeholderIndex)
                            .allowsHitTesting(false)
                    }

                    TextField("", text: $foodDescription, axis: .vertical)
                        .font(.body)
                        .lineLimit(2...5)
                        .textFieldStyle(.plain)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .focused($isFocused)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 10)
                }
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color(.secondarySystemGroupedBackground))
                )

                Button {
                    onSubmit(foodDescription)
                } label: {
                    Text("Analyze")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .disabled(foodDescription.trimmingCharacters(in: .whitespaces).isEmpty)

                Spacer()
            }
            .padding(.horizontal)
            .padding(.top, 20)
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Text Input")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { isFocused = true }
            .onReceive(timer) { _ in
                guard foodDescription.isEmpty else { return }
                withAnimation(.easeInOut(duration: 0.3)) {
                    placeholderIndex = (placeholderIndex + 1) % placeholders.count
                }
            }
        }
    }
}
