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
