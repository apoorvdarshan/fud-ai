import SwiftUI

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

/// Unlike a system Menu, these rows keep the app's colors, border and type at
/// every level. A single presentation owns the entire menu stack.
struct FlashMenuItem {
    fileprivate let title: Text
    fileprivate let icon: String?
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
        swatch: Color? = nil,
        isSelected: Bool = false,
        role: ButtonRole? = nil,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.swatch = swatch
        self.isSelected = isSelected
        self.role = role
        destination = .action(action)
    }

    init(_ title: Text, icon: String, children: [FlashMenuItem]) {
        self.title = title
        self.icon = icon
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

    @State private var presentation: FlashMenuPage?
    @State private var pendingAction: (() -> Void)?

    var body: some View {
        Button {
            // The first sheet render receives the actual rows instead of reading
            // a second piece of state captured by an isPresented closure.
            presentation = FlashMenuPage(title: title, items: items())
        } label: {
            label()
        }
        .buttonStyle(.plain)
        .flashSheet(item: $presentation, onDismiss: menuDidDismiss) { root in
            FlashMenuPanel(root: root) { action in
                pendingAction = action
                presentation = nil
            }
        }
    }

    private func menuDidDismiss() {
        let action = pendingAction
        pendingAction = nil
        // Destinations may themselves present a sheet or the photo picker.
        action?()
    }
}

private struct FlashMenuPage: Identifiable {
    let id = UUID()
    let title: Text
    let items: [FlashMenuItem]
}

private struct FlashMenuPanel: View {
    let root: FlashMenuPage
    let onDismiss: ((() -> Void)?) -> Void
    @State private var subpages: [FlashMenuPage] = []
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let page = subpages.last ?? root
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if !subpages.isEmpty {
                    Button {
                        subpages.removeLast()
                    } label: {
                        Image(systemName: "chevron.backward")
                            .frame(width: 44, height: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Back")
                }
                page.title
                    .font(.system(.title2, weight: .black).width(.condensed))
                    .accessibilityAddTraits(.isHeader)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    onDismiss(nil)
                } label: {
                    Image(systemName: "xmark")
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .foregroundStyle(AppColors.onAccent)
            .background(AppColors.calorie)
            .pinkNeoRule(AppColors.flashRule)

            ScrollView {
                VStack(spacing: 6) {
                    if page.items.isEmpty {
                        Text("None")
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, minHeight: 46, alignment: .leading)
                            .padding(.horizontal, 12)
                            .flashPanel()
                    }
                    ForEach(Array(page.items.enumerated()), id: \.offset) { _, item in
                        menuRow(item)
                    }
                }
                .padding(12)
            }
            .id(page.id)
        }
        .background(AppColors.appBackground.ignoresSafeArea())
        .accessibilityIdentifier("flash.menu")
        .presentationDragIndicator(.hidden)
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large])
    }

    private func menuRow(_ item: FlashMenuItem) -> some View {
        Button(role: item.role) {
            switch item.destination {
            case .action(let action):
                onDismiss(action)
            case .group(let children):
                subpages.append(FlashMenuPage(title: item.title, items: children))
            }
        } label: {
            HStack(spacing: 10) {
                if let swatch = item.swatch {
                    Rectangle()
                        .fill(swatch)
                        .frame(width: 22, height: 22)
                        .pinkNeoRule(AppColors.flashRule)
                        .accessibilityHidden(true)
                } else if let icon = item.icon {
                    Image(systemName: icon)
                        .frame(width: 24)
                        .accessibilityHidden(true)
                }
                item.title
                    .frame(maxWidth: .infinity, alignment: .leading)
                if item.isSelected {
                    Image(systemName: "checkmark")
                        .accessibilityHidden(true)
                } else if case .group = item.destination {
                    Image(systemName: "chevron.forward")
                        .accessibilityHidden(true)
                }
            }
            .font(.system(.body, weight: .bold).width(.condensed))
            .foregroundStyle(item.role == .destructive ? Color.red : Color.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(minHeight: 46)
            .contentShape(Rectangle())
            .flashPanel()
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(item.isSelected ? .isSelected : [])
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
        }
    }

    private var currentValue: some View {
        HStack(spacing: 6) {
            optionTitle(selection)
                .fixedSize(horizontal: false, vertical: true)
            Image(systemName: "chevron.up.chevron.down")
                .font(.caption.weight(.semibold))
                .accessibilityHidden(true)
        }
        .foregroundStyle(AppColors.accentText)
    }

    var body: some View {
        FlashMenu(title: title, items: {
            options.map { option in
                FlashMenuItem(optionTitle(option), icon: icon?(option), swatch: swatch?(option), isSelected: selection == option) {
                    selection = option
                }
            }
        }) {
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
        .accessibilityLabel(title)
        .accessibilityValue(optionTitle(selection))
    }
}
