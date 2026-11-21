import SwiftUI

struct CalorieRangeSettings: View {
    @AppStorage("displayCalorieFloor") private var floor = 0
    @AppStorage("displayCalorieCeiling") private var ceiling = 0
    @AppStorage(EnergyUnit.storageKey) private var energyUnitRaw = EnergyUnit.defaultUnit.rawValue
    @State private var editing = false
    @State private var floorText = ""
    @State private var ceilingText = ""

    private var unit: EnergyUnit { EnergyUnit(rawValue: energyUnitRaw) ?? .defaultUnit }
    /// kJ display needs six digits: 99,999 kcal reopens as 418,396 kJ.
    private var digitLimit: Int { unit == .kj ? 6 : 5 }
    private var parsedFloor: Int? { Self.bound(floorText, maxDigits: digitLimit) }
    private var parsedCeiling: Int? { Self.bound(ceilingText, maxDigits: digitLimit) }
    private var valid: Bool {
        guard let lower = parsedFloor, let upper = parsedCeiling else { return false }
        return lower == 0 || upper == 0 || lower <= upper
    }

    var body: some View {
        Button {
            floorText = floor > 0 ? String(unit.displayValue(kcal: floor)) : ""
            ceilingText = ceiling > 0 ? String(unit.displayValue(kcal: ceiling)) : ""
            editing = true
        } label: {
            LabeledContent("Optional Calorie Range") {
                if floor == 0 && ceiling == 0 { Text("Not set") }
                else {
                    Text(verbatim: [floor, ceiling].map { $0 > 0 ? unit.format(kcal: $0) : "—" }.joined(separator: " – "))
                }
            }
        }
        .sheet(isPresented: $editing) {
            NavigationStack {
                Form {
                    Section {
                        TextField("Calorie floor (\(unit.symbol))", text: $floorText).keyboardType(.numberPad)
                        TextField("Calorie ceiling (\(unit.symbol))", text: $ceilingText).keyboardType(.numberPad)
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
                            floor = Self.storedKcal(displayed: lower, unit: unit)
                            ceiling = Self.storedKcal(displayed: upper, unit: unit)
                            editing = false
                        }.disabled(!valid)
                    }
                }
            }
        }
    }

    /// The range is edited in the user's display unit but stored in kcal.
    static func storedKcal(displayed: Int, unit: EnergyUnit) -> Int {
        unit.kcal(fromDisplayed: Double(displayed))
    }

    static func bound(_ text: String, maxDigits: Int = 5) -> Int? {
        if text.isEmpty { return 0 }
        let digits = text.compactMap(\.wholeNumberValue)
        guard digits.count == text.count, digits.count <= maxDigits, digits.allSatisfy({ $0 < 10 }),
              let value = Int(digits.map(String.init).joined()), value > 0 else { return nil }
        return value
    }
}
