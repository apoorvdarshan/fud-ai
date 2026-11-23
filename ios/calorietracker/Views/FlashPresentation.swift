import SwiftUI
import UIKit

extension View {
    /// The system still manages presentation, focus, detents and dismissal.
    /// Only the app's sheet surface and tint are replaced.
    func flashSheet<SheetContent: View>(
        isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping () -> SheetContent
    ) -> some View {
        sheet(isPresented: isPresented, onDismiss: onDismiss) {
            content().flashPresentation()
        }
    }

    func flashSheet<Item: Identifiable, SheetContent: View>(
        item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil,
        @ViewBuilder content: @escaping (Item) -> SheetContent
    ) -> some View {
        sheet(item: item, onDismiss: onDismiss) { item in
            content(item).flashPresentation()
        }
    }

    func flashPopover<PopoverContent: View>(
        isPresented: Binding<Bool>,
        @ViewBuilder content: @escaping () -> PopoverContent
    ) -> some View {
        popover(isPresented: isPresented) {
            content().flashPresentation()
        }
    }

    func flashPanel(lineWidth: CGFloat = 1) -> some View {
        background(AppColors.appCard)
            .pinkNeoRule(AppColors.flashRule, width: lineWidth)
    }

    func flashScreenSurface() -> some View {
        scrollContentBackground(.hidden)
            .background(AppColors.appBackground.ignoresSafeArea())
    }

    func flashListRows() -> some View {
        listRowBackground(
            Rectangle()
                .fill(AppColors.appCard)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(AppColors.flashRule.opacity(0.32))
                        .frame(height: 1)
                }
        )
        .listRowSeparator(.hidden)
    }

    func flashEditorSurface() -> some View {
        flashScreenSurface()
            .listStyle(.plain)
            .listSectionSpacing(12)
            .contentMargins(.horizontal, 12, for: .scrollContent)
    }

    func flashSectionRows() -> some View {
        listRowBackground(
            Rectangle()
                .fill(AppColors.appCard)
                .overlay(alignment: .leading) {
                    Rectangle().fill(AppColors.flashRule).frame(width: 1)
                }
                .overlay(alignment: .trailing) {
                    Rectangle().fill(AppColors.flashRule).frame(width: 1)
                }
        )
        .listRowSeparatorTint(AppColors.flashRule.opacity(0.24))
        .listSectionSeparatorTint(AppColors.flashRule)
    }

    func flashSectionHeading() -> some View {
        font(.system(.subheadline, weight: .black).width(.condensed))
            .textCase(.uppercase)
            .foregroundStyle(Color.primary)
            .accessibilityAddTraits(.isHeader)
    }

    func flashActionFill() -> some View {
        foregroundStyle(AppColors.onAccent)
            .background(AppColors.calorie)
            .pinkNeoRule(AppColors.flashRule)
    }

    private func flashPresentation() -> some View {
        tint(AppColors.accentText)
            .scrollContentBackground(.hidden)
            .presentationBackground(AppColors.appCard)
            .presentationCornerRadius(0)
    }
}

struct FlashActionButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.headline, weight: .black).width(.condensed))
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .foregroundStyle(isEnabled ? AppColors.onAccent : Color.secondary)
            .background(isEnabled ? AppColors.calorie : AppColors.appCard)
            .pinkNeoRule(AppColors.flashRule)
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}

/// A scrollable panel keeps the wheel and its action reachable at larger
/// text sizes. The navigation toolbar remains native.
struct FlashGoalPickerBody<Wheel: View>: View {
    let heading: LocalizedStringKey
    let actionTitle: LocalizedStringKey
    var actionIcon: String? = nil
    let action: () -> Void
    @ViewBuilder let wheel: () -> Wheel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(heading)
                    .font(.system(.title2, weight: .black).width(.condensed))
                    .accessibilityAddTraits(.isHeader)
                wheel()
                    .frame(maxWidth: .infinity)
                    .flashPanel()
                Button(action: action) {
                    Group {
                        if let actionIcon {
                            Label(actionTitle, systemImage: actionIcon)
                        } else {
                            Text(actionTitle)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(FlashActionButtonStyle())
            }
            .frame(maxWidth: 520)
            .padding(16)
            .frame(maxWidth: .infinity)
        }
        .flashScreenSurface()
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
    }
}

struct FlashFoodPhoto<Content: View>: View {
    let count: Int
    @ViewBuilder let content: () -> Content

    var body: some View {
        Rectangle()
            .fill(AppColors.appCard)
            .aspectRatio(4.0 / 3, contentMode: .fit)
            .containerRelativeFrame(.horizontal) { width, _ in
                min(width * (count > 1 ? 0.84 : 1), 440)
            }
            .overlay { content() }
            .clipped()
            .contentShape(Rectangle())
    }
}

struct FlashFoodNameField: View {
    @Binding var name: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Name")
                .font(.subheadline.weight(.semibold))
                .accessibilityHidden(true)
            TextField("Food name", text: Binding(
                get: { name },
                set: { name = $0.replacingOccurrences(of: "\n", with: " ") }
            ), axis: .vertical)
                .lineLimit(1...3)
                .accessibilityIdentifier("food.editor.name")
        }
        .padding(.vertical, 5)
    }
}

struct FlashQuantityRow<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Text("Quantity")
                .frame(maxWidth: .infinity, alignment: .leading)
            content()
        }
    }
}

/// Shared menu content. SwiftUI owns the anchored menu, including its glass,
/// submenu navigation, focus, dismissal, and adaptation at large text sizes.
struct FlashMenuItem {
    fileprivate let title: Text
    fileprivate let icon: String?
    fileprivate let iconAsset: String?
    fileprivate let swatch: Color?
    fileprivate let isSelected: Bool
    fileprivate let role: ButtonRole?
    fileprivate let destination: Destination

    fileprivate enum Destination {
        case action(() -> Void)
        case group([FlashMenuItem])
    }

    init(
        _ title: Text,
        icon: String? = nil,
        iconAsset: String? = nil,
        swatch: Color? = nil,
        isSelected: Bool = false,
        role: ButtonRole? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.iconAsset = iconAsset
        self.swatch = swatch
        self.isSelected = isSelected
        self.role = role
        destination = .action(action)
    }

    init(_ title: Text, icon: String, children: [FlashMenuItem]) {
        self.title = title
        self.icon = icon
        iconAsset = nil
        swatch = nil
        isSelected = false
        role = nil
        destination = .group(children)
    }
}

struct FlashMenu<Trigger: View>: View {
    let title: Text
    let items: () -> [FlashMenuItem]
    @ViewBuilder let label: () -> Trigger

    var body: some View {
        Menu {
            let rows = items()
            if rows.isEmpty {
                Button("None", action: {}).disabled(true)
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, item in
                FlashNativeMenuRow(item: item)
            }
        } label: {
            label()
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .menuOrder(.fixed)
        .accessibilityLabel(title)
    }
}

private struct FlashNativeMenuRow: View {
    let item: FlashMenuItem

    var body: some View {
        switch item.destination {
        case .action(let action):
            Button(role: item.role, action: action) {
                FlashNativeMenuLabel(title: item.title,
                                     icon: item.isSelected ? "checkmark" : item.icon,
                                     iconAsset: item.iconAsset, swatch: item.isSelected ? nil : item.swatch)
            }
            .accessibilityAddTraits(item.isSelected ? .isSelected : [])
        case .group(let children):
            Menu {
                ForEach(Array(children.enumerated()), id: \.offset) { _, child in
                    FlashNativeMenuRow(item: child)
                }
            } label: {
                FlashNativeMenuLabel(title: item.title, icon: item.icon)
            }
        }
    }
}

private struct FlashNativeMenuLabel: View {
    let title: Text
    var icon: String? = nil
    var iconAsset: String? = nil
    var swatch: Color? = nil

    var body: some View {
        if let swatch {
            let color = UIColor(swatch)
            let image = UIImage(systemName: "square.fill")?.withTintColor(color, renderingMode: .alwaysOriginal)
            if let image {
                Label { title } icon: { Image(uiImage: image) }
            } else {
                title
            }
        } else if let icon {
            Label { title } icon: { Image(systemName: icon) }
        } else if let iconAsset {
            Label { title } icon: { Image(iconAsset) }
        } else {
            title
        }
    }
}

struct FlashChoicePicker<Value: Hashable>: View {
    let title: Text
    @Binding var selection: Value
    let options: [Value]
    var titleIcon: String? = nil
    var icon: ((Value) -> String?)? = nil
    var swatch: ((Value) -> Color?)? = nil
    let optionTitle: (Value) -> Text

    private var titleLabel: some View {
        HStack(spacing: 8) {
            if let titleIcon {
                Image(systemName: titleIcon)
                    .foregroundStyle(AppColors.accentText)
                    .frame(width: 24)
                    .accessibilityHidden(true)
            }
            title
                .font(.system(.body, weight: .semibold).width(.condensed))
        }
    }

    private var currentValue: some View {
        HStack(spacing: 6) {
            optionTitle(selection)
                .font(.system(.subheadline, weight: .semibold).width(.condensed))
                .fixedSize(horizontal: false, vertical: true)
            Image(systemName: "chevron.up.chevron.down")
                .font(.caption.weight(.semibold))
                .accessibilityHidden(true)
        }
        .foregroundStyle(AppColors.accentText)
    }

    var body: some View {
        Menu {
            Picker(selection: $selection) {
                ForEach(options, id: \.self) { option in
                    FlashNativeMenuLabel(title: optionTitle(option), icon: icon?(option), swatch: swatch?(option))
                        .tag(option)
                }
            } label: {
                title
            }
            .pickerStyle(.inline)
        } label: {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    titleLabel.fixedSize()
                    Spacer(minLength: 4)
                    currentValue.fixedSize()
                }
                VStack(alignment: .leading, spacing: 4) {
                    titleLabel
                    currentValue
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .menuStyle(.borderlessButton)
        .menuIndicator(.hidden)
        .menuOrder(.fixed)
        .accessibilityLabel(title)
        .accessibilityValue(optionTitle(selection))
    }
}
