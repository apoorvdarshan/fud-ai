import SwiftUI

/// Presentation only: energy and nutrition values are still supplied by Home's stores.
struct PinkNeoPalette {
    let dark: Bool
    let gray: Bool
    let theme: AppThemeColor

    private var pink: Bool { theme == .fudPink }
    var paper: Color { gray || !pink ? AppColors.appBackground : Color(hex: dark ? PinkNeoFlash.paperDark : PinkNeoFlash.paperLight) }
    var panel: Color { gray || !pink ? AppColors.appCard : Color(hex: dark ? PinkNeoFlash.panelDark : PinkNeoFlash.panelLight) }
    var ink: Color { Color(hex: dark ? PinkNeoFlash.inkDark : PinkNeoFlash.inkLight) }
    var rule: Color { pink && !gray ? ink : ink.opacity(dark ? 0.75 : 0.82) }
    var accent: Color { action }
    private var actionHex: UInt { pink ? PinkNeoFlash.pink : theme.startHex }
    var action: Color { Color(hex: actionHex) }
    var onAction: Color { AppColors.contrastInk(on: actionHex) }
    var emptyActionSegment: Color { onAction == .white ? .black : .white }
    var wash: Color { pink && dark && !gray ? Color(hex: PinkNeoFlash.railDark) : panel }
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
        overlay(Rectangle().strokeBorder(color, lineWidth: width).allowsHitTesting(false).accessibilityHidden(true))
    }
}

struct PinkNeoRailItem: Identifiable {
    let id: String
    let title: LocalizedStringKey
    let icon: String
    var selected = false
    var badge = false
    let action: () -> Void
}

struct PinkNeoRail: View {
    let items: [PinkNeoRailItem]
    let onCamera: () -> Void
    private var appearance = PinkNeoAppearance()
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .caption2) private var railWidth = 64.0

    var body: some View {
        let palette = appearance.palette
        VStack(spacing: 4) {
            ScrollView {
                VStack(spacing: 4) {
                    Image("onboardingLogo")
                        .resizable().scaledToFit()
                        .frame(width: 27, height: 27)
                        .frame(width: 36, height: 36)
                        .background(Color.black, in: RoundedRectangle(cornerRadius: 7))
                        .padding(.top, 5)
                        .accessibilityHidden(true)
                    Text(verbatim: "FUD AI")
                        .font(.system(size: 9, weight: .black))
                        .accessibilityHidden(true)
                    Rectangle().fill(palette.rule).frame(height: 1).padding(.vertical, 4)
                        .accessibilityHidden(true)
                    ForEach(items) { item in
                        railButton(item, palette: palette)
                    }
                }
            }
            .scrollIndicators(.hidden)
            Rectangle().fill(palette.rule).frame(height: 1).accessibilityHidden(true)
            railButton(
                PinkNeoRailItem(id: "nav.camera", title: "Camera", icon: "camera", action: onCamera),
                palette: palette,
                outlined: true
            )
        }
        .padding(4)
        .frame(width: min(railWidth, 76))
        .foregroundStyle(palette.ink)
        .background(palette.wash, in: RoundedRectangle(cornerRadius: 9))
        .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(palette.rule, lineWidth: 1).allowsHitTesting(false))
        .padding(.vertical, 7)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("nav.rail")
    }

    private func railButton(_ item: PinkNeoRailItem, palette: PinkNeoPalette, outlined: Bool = false) -> some View {
        Button(action: item.action) {
            VStack(spacing: 3) {
                Image(systemName: item.icon)
                    .font(.system(size: 17, weight: .semibold))
                    .overlay(alignment: .topTrailing) {
                        if item.badge {
                            Circle()
                                .fill(item.selected ? palette.onAction : palette.action)
                                .frame(width: 7, height: 7)
                                .offset(x: 5, y: -3)
                        }
                    }
                if !typeSize.isAccessibilitySize {
                    Text(item.title)
                        .font(.caption2.weight(.semibold))
                        .lineLimit(2)
                        .minimumScaleFactor(0.75)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.vertical, 5)
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(item.selected ? palette.onAction : palette.ink)
            .background(item.selected ? palette.action : (outlined ? palette.panel : Color.clear),
                         in: RoundedRectangle(cornerRadius: 5))
            .overlay {
                if outlined {
                    RoundedRectangle(cornerRadius: 5).strokeBorder(palette.rule, lineWidth: 1)
                        .allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(item.title))
        .accessibilityValue(item.badge ? Text("Update Available") : Text(verbatim: ""))
        .accessibilityAddTraits(item.selected ? .isSelected : [])
        .accessibilityIdentifier(item.id)
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
            VStack(alignment: .leading, spacing: 1) {
                Group {
                    if Calendar.current.isDateInToday(date) { Text("Today") }
                    else { Text(date, format: .dateTime.weekday(.wide)) }
                }
                .textCase(.uppercase)
                .font(.system(.largeTitle, weight: .black).width(.condensed))
                .accessibilityAddTraits(.isHeader)

                Text(date, format: .dateTime.weekday(.wide).month(.wide).day())
                    .font(.system(.subheadline, weight: .semibold).width(.condensed))
            }
            Spacer(minLength: 0)
            Button {
                calendarDate = min(date, .now)
                showCalendar = true
            } label: {
                Image(systemName: "calendar")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .background(palette.wash)
                    .pinkNeoRule(palette.rule)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Date")
            .accessibilityValue(Text(date, style: .date))
            .accessibilityIdentifier("home.calendar")
            .flashPopover(isPresented: $showCalendar) {
                DatePicker("Date", selection: $calendarDate, in: ...Date.now, displayedComponents: .date)
                    .datePickerStyle(.graphical)
                    .padding()
                    .onChange(of: calendarDate) { _, day in
                        date = day
                        showCalendar = false
                    }
            }
        }
        .padding(10)
        .foregroundStyle(palette.ink)
        .background(palette.panel)
        .pinkNeoRule(palette.rule, width: 1.5)
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
