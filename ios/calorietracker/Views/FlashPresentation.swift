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
                .pinkNeoRule(AppColors.flashRule)
        )
        .listRowSeparator(.hidden)
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

    @State private var isPresented = false
    @State private var pages: [Page] = []
    @State private var pendingAction: (() -> Void)?

    private struct Page {
        let title: Text
        let items: [FlashMenuItem]
    }

    var body: some View {
        Button {
            pages = [Page(title: title, items: items())]
            isPresented = true
        } label: {
            label()
        }
        .buttonStyle(.plain)
        .flashSheet(isPresented: $isPresented, onDismiss: menuDidDismiss) {
            menuPanel
                .presentationDetents([.medium, .large])
        }
    }

    private var menuPanel: some View {
        let page = pages.last ?? Page(title: title, items: [])
        return VStack(spacing: 0) {
            HStack(spacing: 8) {
                if pages.count > 1 {
                    Button {
                        pages.removeLast()
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
                    isPresented = false
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
                    ForEach(Array(page.items.enumerated()), id: \.offset) { _, item in
                        menuRow(item)
                    }
                }
                .padding(12)
            }
            .id(pages.count)
        }
        .background(AppColors.appBackground.ignoresSafeArea())
        .accessibilityIdentifier("flash.menu")
    }

    private func menuRow(_ item: FlashMenuItem) -> some View {
        Button(role: item.role) {
            switch item.destination {
            case .action(let action):
                pendingAction = action
                isPresented = false
            case .group(let children):
                pages.append(Page(title: item.title, items: children))
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

    private func menuDidDismiss() {
        let action = pendingAction
        pendingAction = nil
        pages = []
        // Destinations may themselves present a sheet or the photo picker.
        action?()
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

    var body: some View {
        FlashMenu(title: title, items: {
            options.map { option in
                FlashMenuItem(optionTitle(option), icon: icon?(option), swatch: swatch?(option), isSelected: selection == option) {
                    selection = option
                }
            }
        }) {
            HStack(spacing: 8) {
                if let titleIcon {
                    Image(systemName: titleIcon)
                        .foregroundStyle(AppColors.accentText)
                        .frame(width: 24)
                        .accessibilityHidden(true)
                }
                title
                    .frame(maxWidth: .infinity, alignment: .leading)
                optionTitle(selection)
                    .foregroundStyle(AppColors.accentText)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AppColors.accentText)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel(title)
        .accessibilityValue(optionTitle(selection))
    }
}
