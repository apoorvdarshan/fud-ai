import SwiftUI
import UIKit

/// Presentation only: energy and nutrition values are still supplied by Home's stores.
struct PinkNeoPalette {
    let dark: Bool
    let gray: Bool
    let theme: AppThemeColor

    var paper: Color { AppColors.appBackground }
    var panel: Color { AppColors.appCard }
    var ink: Color { Color(hex: dark ? PinkNeoFlash.inkDark : PinkNeoFlash.inkLight) }
    var rule: Color { AppColors.flashRule.opacity(0.45) }
    var accent: Color { action }
    private var actionHex: UInt { theme.startHex }
    var action: Color { Color(hex: actionHex) }
    var onAction: Color { AppColors.contrastInk(on: actionHex) }
    var emptyActionSegment: Color { onAction == .white ? .black : .white }
    var wash: Color { panel }
}

struct PinkNeoAppearance: DynamicProperty {
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(AppThemeColor.storageKey) private var theme = AppThemeColor.defaultColor
    @AppStorage("appearanceMode") private var appearance = "system"

    var palette: PinkNeoPalette {
        PinkNeoPalette(dark: colorScheme == .dark, gray: appearance == "darkGray", theme: theme)
    }
}

extension View {
    func pinkNeoRule(_ color: Color, width: CGFloat = 1) -> some View {
        overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(color, lineWidth: width)
            .allowsHitTesting(false).accessibilityHidden(true))
    }

}

struct PinkNeoDateHeader: View {
    @Binding var date: Date
    @State private var showCalendar = false
    @State private var calendarDate = Date.now
    private var appearance = PinkNeoAppearance()

    var body: some View {
        let palette = appearance.palette
        HStack(spacing: 8) {
            Group {
                if Calendar.current.isDateInToday(date) { Text("Today") }
                else { Text(date, format: .dateTime.weekday(.abbreviated).month(.abbreviated).day()) }
            }
            .font(.system(.headline, design: .rounded, weight: .semibold))
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("home.dateHeading")
            Spacer(minLength: 0)
            Button {
                calendarDate = min(date, .now)
                showCalendar = true
            } label: {
                Image(systemName: "calendar")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Date")
            .accessibilityValue(Text(date, style: .date))
            .accessibilityIdentifier("home.calendar")
            .flashPopover(isPresented: $showCalendar) {
                DatePicker("Date", selection: $calendarDate, in: ...Date.now, displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .frame(idealWidth: 304)
                    .padding(6)
                    .accessibilityIdentifier("home.calendarPopup")
                    .presentationCompactAdaptation(.popover)
                    .onChange(of: calendarDate) { _, day in
                        guard !Calendar.current.isDate(date, inSameDayAs: day) else { return }
                        AppHaptics.selection()
                        date = day
                        showCalendar = false
                    }
            }
        }
        .padding(.leading, 2)
        .foregroundStyle(palette.ink)
    }
}

struct PinkNeoCalorieSummary: View {
    let eaten: Int
    let goal: Int
    let burnLine: String?
    let launchFillEpoch: Int
    @AppStorage(EnergyUnit.storageKey) private var unit = EnergyUnit.defaultUnit
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var shown = 0.0
    @State private var lastEpoch: Int? = nil
    private var appearance = PinkNeoAppearance()

    private var progress: Double { goal > 0 ? max(0, min(1, Double(eaten) / Double(goal))) : 0 }
    private var displayedEaten: Int { unit.displayValue(kcal: eaten) }
    private var displayedGoal: Int { unit.displayValue(kcal: goal) }
    private var status: String {
        guard goal > 0 else { return String(localized: "No goal") }
        if eaten < goal { return String(localized: "\((displayedGoal - displayedEaten).formatted()) left") }
        if eaten > goal { return String(localized: "\((displayedEaten - displayedGoal).formatted()) over") }
        return String(localized: "Goal reached")
    }

    var body: some View {
        let palette = appearance.palette
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: 7)) : AnyLayout(HStackLayout(spacing: 7))
        VStack(alignment: .leading, spacing: 6) {
            layout {
                VStack(alignment: .leading, spacing: 6) {
                    Text(unit == .kj ? String(localized: "Kilojoules") : LocalizedDisplayText.text("Calories"))
                        .font(.system(.caption, weight: .black).width(.condensed))
                        .textCase(.uppercase)
                    Text(unit.format(kcal: eaten))
                        .font(.system(.largeTitle, weight: .black).width(.condensed))
                        .monospacedDigit()
                        .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                        .minimumScaleFactor(0.55)
                    Text(status)
                        .font(.system(.footnote, weight: .bold).width(.condensed))
                     PinkNeoEnergySegments(progress: shown, ink: palette.onAction, track: palette.emptyActionSegment)
                    .frame(height: 10)
                    .accessibilityHidden(true)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                .padding(11)
                .foregroundStyle(palette.onAction)
                .background(palette.action)
                .pinkNeoRule(palette.rule, width: 1.5)

                VStack(spacing: 5) {
                    Text("Goal")
                        .font(.system(.caption, weight: .black).width(.condensed))
                        .textCase(.uppercase)
                    Image(systemName: "scope")
                        .font(.title3.weight(.bold))
                        .accessibilityHidden(true)
                    Text(goal > 0 ? unit.format(kcal: goal) : "—")
                        .font(.system(.headline, weight: .black).width(.condensed))
                        .monospacedDigit()
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.65)
                }
                .frame(maxWidth: typeSize.isAccessibilitySize ? .infinity : 84, maxHeight: .infinity)
                .padding(.vertical, 11)
                .padding(.horizontal, 4)
                .foregroundStyle(palette.ink)
                .background(palette.panel)
                .pinkNeoRule(palette.rule, width: 1.5)
            }
            .fixedSize(horizontal: false, vertical: true)
            if let burnLine {
                Text(burnLine).font(.caption).foregroundStyle(palette.ink)
            }
        }
        .accessibilityIdentifier("home.calorieSummary")
        .onAppear { updateProgress(replay: lastEpoch != launchFillEpoch) }
        .onChange(of: launchFillEpoch) { _, _ in updateProgress(replay: true) }
        .onChange(of: progress) { _, _ in updateProgress() }
    }

    private func updateProgress(replay: Bool = false) {
        lastEpoch = launchFillEpoch
        if replay || reduceMotion { shown = reduceMotion ? progress : 0 }
        guard !reduceMotion else { return }
        withAnimation(.easeOut(duration: 0.45)) { shown = progress }
    }
}

/// One animatable drawing instead of measuring twenty SwiftUI subtrees on each frame.
private struct PinkNeoEnergySegments: View, Animatable {
    var progress: Double
    let ink: Color
    let track: Color

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        Canvas { context, size in
            let count = 20
            let gap = min(2, size.width / 40)
            let width = (size.width - gap * CGFloat(count - 1)) / CGFloat(count)
            guard width > 0 else { return }
            var filled = Path()
            var unfilled = Path()
            for index in 0..<count {
                let x = CGFloat(index) * (width + gap)
                let fraction = max(0, min(1, progress * Double(count) - Double(index)))
                let fillWidth = width * CGFloat(fraction)
                if fillWidth > 0 {
                    filled.addRect(CGRect(x: x, y: 0, width: fillWidth, height: size.height))
                }
                if fillWidth < width {
                    unfilled.addRect(CGRect(x: x + fillWidth, y: 0, width: width - fillWidth, height: size.height))
                }
            }
            context.fill(unfilled, with: .color(track))
            context.fill(filled, with: .color(ink))
        }
    }
}

struct PinkNeoNutrient: View {
    let label: String
    let current: Double
    let goal: Double
    let unit: String
    let palette: PinkNeoPalette
    var compact = false
    @Environment(\.dynamicTypeSize) private var typeSize

    private var progress: Double { goal > 0 ? max(0, min(1, current / goal)) : 0 }
    private var status: String {
        guard goal > 0 else { return String(localized: "No goal") }
        if abs(goal - current) < 0.0001 { return String(localized: "Goal reached") }
        let amount = MacroValueFormatter.display(abs(goal - current))
        return goal > current ? String(localized: "\(amount)\(unit) left") : String(localized: "\(amount)\(unit) over")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            let layout = compact && !typeSize.isAccessibilitySize
                ? AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 6))
                : AnyLayout(VStackLayout(alignment: .leading, spacing: 5))
            layout {
                Text(LocalizedDisplayText.text(label))
                    .font(.system(.caption2, weight: .black).width(.condensed))
                    .textCase(.uppercase)
                    .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                    .minimumScaleFactor(0.65)
                    .accessibilityLabel(LocalizedDisplayText.text(label))
                Text("\(MacroValueFormatter.string(current))\(unit.hasPrefix(" ") ? "" : " ")\(unit)")
                    .font(.system(.headline, weight: .black).width(.condensed))
                    .monospacedDigit()
                    .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                    .minimumScaleFactor(0.6)
            }
            GeometryReader { proxy in
                Rectangle().fill(palette.ink.opacity(0.14))
                    .overlay(alignment: .leading) {
                        Rectangle().fill(palette.accent).frame(width: proxy.size.width * progress)
                    }
            }
            .frame(height: 3)
            .accessibilityHidden(true)
            Text(status)
                .font(.system(.caption2, weight: .medium).width(.condensed))
                .lineLimit(typeSize.isAccessibilitySize ? nil : 1)
                .minimumScaleFactor(0.62)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(6)
        .foregroundStyle(palette.ink)
        .background(palette.panel)
        .pinkNeoRule(palette.rule, width: 0.5)
        .accessibilityElement(children: .contain)
    }
}
