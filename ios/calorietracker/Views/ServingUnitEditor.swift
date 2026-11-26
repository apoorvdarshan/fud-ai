import SwiftUI

struct ServingUnitEditor: View {
    @Binding var quantityText: String
    @Binding var servingSizeGrams: Double
    @Binding var selectedUnitID: String

    let unitOptions: [ServingUnitOption]
    var allowsGramUnit = true
    let focusRequest: Int
    var onEditingChanged: (Bool) -> Void
    var onClear: () -> Void

    private var pickerOptions: [ServingUnitOption] {
        let options = ServingUnitOption.pickerOptions(for: unitOptions)
        return allowsGramUnit ? options : options.filter { !$0.isGramUnit }
    }

    private var selectedOption: ServingUnitOption {
        pickerOptions.first { $0.id == selectedUnitID }
            ?? pickerOptions.first
            ?? .grams
    }

    private var selectedQuantity: Double? {
        ServingAmountExpression.evaluate(quantityText)
    }

    private var selectedUnitLabel: String {
        displayUnit(for: selectedOption, quantity: selectedQuantity)
    }

    var body: some View {
        HStack(spacing: 8) {
            EndEditingDecimalTextField(
                text: $quantityText,
                focusRequest: focusRequest,
                onEditingChanged: { editing in
                    if !editing {
                        finalizeQuantityExpression()
                    }
                    onEditingChanged(editing)
                },
                showsCalculatorToolbar: true
            )
            .frame(width: 104)
            .onChange(of: quantityText) { _, newValue in
                guard let parsed = ServingAmountExpression.evaluate(newValue), parsed > 0 else { return }
                servingSizeGrams = parsed * selectedOption.gramsPerUnit
            }
            .onChange(of: selectedUnitID) { _, _ in
                syncQuantityTextToSelectedUnit()
            }

            if !quantityText.isEmpty {
                Button(action: onClear) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear quantity")
            }

            if pickerOptions.count > 1 {
                Menu {
                    ForEach(pickerOptions) { option in
                        Button {
                            selectedUnitID = option.id
                        } label: {
                            Text(displayUnit(for: option, quantity: option.id == selectedUnitID ? selectedQuantity : nil))
                        }
                    }
                } label: {
                    HStack(spacing: 3) {
                        Text(selectedUnitLabel)
                            .lineLimit(1)
                            .minimumScaleFactor(0.78)
                            .allowsTightening(true)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption.weight(.semibold))
                    }
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 90, alignment: .trailing)
                }
                .buttonStyle(.plain)
                .fixedSize(horizontal: true, vertical: false)
            } else {
                Text(selectedUnitLabel)
                    .foregroundStyle(.secondary)
                    .frame(
                        width: selectedOption.normalizedUnit == "serving" ? 90 : 36,
                        alignment: .leading
                    )
            }
        }
    }

    private func displayUnit(for option: ServingUnitOption, quantity: Double?) -> String {
        if option.normalizedUnit == "serving" {
            return String(localized: "Serving")
        }
        return option.displayUnit(for: quantity)
    }

    private func syncQuantityTextToSelectedUnit() {
        let option = selectedOption
        let quantity = option.gramsPerUnit > 0 ? servingSizeGrams / option.gramsPerUnit : servingSizeGrams
        quantityText = Self.formatQuantity(quantity)
    }

    private func finalizeQuantityExpression() {
        let trimmed = quantityText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        guard let quantity = ServingAmountExpression.evaluate(trimmed), quantity > 0 else {
            syncQuantityTextToSelectedUnit()
            return
        }
        quantityText = Self.formatQuantity(quantity)
        servingSizeGrams = quantity * selectedOption.gramsPerUnit
    }

    static func formatQuantity(_ value: Double) -> String {
        if value == value.rounded(), value >= Double(Int.min), value <= Double(Int.max) {
            return String(Int(value))
        }
        if abs(value) < 10 {
            return String(format: "%.2f", value).trimmingTrailingZeros()
        }
        return String(format: "%.1f", value).trimmingTrailingZeros()
    }

    /// Parse a decimal string — accepts both "." (C locale) and "," (user locale).
    /// Tries C-locale parsing first, then locale-aware as fallback.
    static func parseDecimal(_ string: String, locale: Locale = .current) -> Double? {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if let value = Double(trimmed) {
            return value
        }
        guard let decimalSeparator = locale.decimalSeparator,
              decimalSeparator != ".",
              trimmed.contains(decimalSeparator)
        else { return nil }

        var normalized = trimmed
        if let groupingSeparator = locale.groupingSeparator, groupingSeparator != decimalSeparator {
            normalized = normalized.replacingOccurrences(of: groupingSeparator, with: "")
        }
        normalized = normalized.replacingOccurrences(of: decimalSeparator, with: ".")
        return Double(normalized)
    }
}

private extension String {
    func trimmingTrailingZeros() -> String {
        var value = self
        while value.contains(".") && value.last == "0" {
            value.removeLast()
        }
        if value.last == "." {
            value.removeLast()
        }
        return value
    }
}

extension ServingUnitOption {
    static func normalizedOptions(_ options: [ServingUnitOption], totalGrams: Double) -> [ServingUnitOption] {
        var seen = Set<String>()
        var normalized: [ServingUnitOption] = []

        for rawOption in options {
            var option = rawOption
            if option.quantity == nil, option.gramsPerUnit > 0 {
                option.quantity = totalGrams / option.gramsPerUnit
            }
            guard option.isValid, !option.isGramUnit, !seen.contains(option.id) else { continue }
            seen.insert(option.id)
            normalized.append(option)
        }

        return Array(normalized.prefix(4))
    }

    static func pickerOptions(for options: [ServingUnitOption]) -> [ServingUnitOption] {
        var seen: Set<String> = [ServingUnitOption.grams.id]
        let nonGramOptions = options.filter { option in
            option.isValid && !option.isGramUnit && seen.insert(option.id).inserted
        }
        return [ServingUnitOption.grams] + nonGramOptions
    }

    static func option(matching id: String, in options: [ServingUnitOption]) -> ServingUnitOption {
        pickerOptions(for: options).first { $0.id == id } ?? .grams
    }

    static func initialUnitID(
        preferredUnit: String?,
        options: [ServingUnitOption],
        defaultToGrams: Bool = false
    ) -> String {
        let pickerOptions = pickerOptions(for: options)
        if let preferredUnit {
            let preferredID = preferredUnit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if pickerOptions.contains(where: { $0.id == preferredID }) {
                return preferredID
            }
        }
        if defaultToGrams {
            return ServingUnitOption.grams.id
        }
        return options.first?.id ?? ServingUnitOption.grams.id
    }

    static func initialQuantityText(
        totalGrams: Double,
        selectedUnitID: String,
        selectedQuantity: Double?,
        options: [ServingUnitOption]
    ) -> String {
        let option = option(matching: selectedUnitID, in: options)
        if let selectedQuantity, selectedQuantity > 0, !option.isGramUnit {
            return ServingUnitEditor.formatQuantity(selectedQuantity)
        }
        let quantity = option.gramsPerUnit > 0 ? totalGrams / option.gramsPerUnit : totalGrams
        return ServingUnitEditor.formatQuantity(quantity)
    }
}
