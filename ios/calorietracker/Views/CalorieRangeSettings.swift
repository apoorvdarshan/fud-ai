import SwiftUI

struct CalorieRangeSettings: View {
    @AppStorage("displayCalorieFloor") private var floor = 0
    @AppStorage("displayCalorieCeiling") private var ceiling = 0
    @State private var editing = false
    @State private var floorText = ""
    @State private var ceilingText = ""

    private var parsedFloor: Int? { Self.bound(floorText) }
    private var parsedCeiling: Int? { Self.bound(ceilingText) }
    private var valid: Bool {
        guard let lower = parsedFloor, let upper = parsedCeiling else { return false }
        return lower == 0 || upper == 0 || lower <= upper
    }

    var body: some View {
        Button {
            floorText = floor > 0 ? floor.formatted(.number.grouping(.never)) : ""
            ceilingText = ceiling > 0 ? ceiling.formatted(.number.grouping(.never)) : ""
            editing = true
        } label: {
            LabeledContent("Optional Calorie Range") {
                if floor == 0 && ceiling == 0 { Text("Not set") }
                else {
                    Text(verbatim: [floor, ceiling].map { $0 > 0 ? EnergyUnit.current.format(kcal: $0) : "—" }.joined(separator: " – "))
                }
            }
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                Form {
                    Section {
                        TextField("Calorie floor (kcal)", text: $floorText).keyboardType(.numberPad)
                        TextField("Calorie ceiling (kcal)", text: $ceilingText).keyboardType(.numberPad)
                    } footer: {
                        Text("Leave either bound blank to skip it. This range is for reference and does not change your targets.")
                    }
                }
                .navigationTitle("Optional Calorie Range")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editing = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            guard valid, let lower = parsedFloor, let upper = parsedCeiling else { return }
                            floor = lower
                            ceiling = upper
                            editing = false
                        }.disabled(!valid)
                    }
                }
            }
        }
    }

    static func bound(_ text: String) -> Int? {
        if text.isEmpty { return 0 }
        let digits = text.compactMap(\.wholeNumberValue)
        guard digits.count == text.count, digits.count <= 5, digits.allSatisfy({ $0 < 10 }),
              let value = Int(digits.map(String.init).joined()), value > 0 else { return nil }
        return value
    }
}
