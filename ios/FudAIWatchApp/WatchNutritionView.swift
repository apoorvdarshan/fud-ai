import SwiftUI

/// Mini iPhone-Home for the wrist: the speedometer calorie gauge on top and the
/// user's Home nutrient pillars beneath (Water is the locked 4th pillar when
/// tracking is enabled — same as iPhone), with the Flash paper, ink and accent.
struct WatchNutritionView: View {
    @EnvironmentObject private var receiver: WatchSnapshotReceiver
    @State private var showsWaterLogger = false

    private var accent: Color { Color(hex: PinkNeoFlash.snapshotAccent(start: receiver.snapshot.themeStartHex)) }
    private let ink = Color(hex: PinkNeoFlash.inkDark)

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Label {
                    Text("Today")
                } icon: {
                    Image(systemName: "flame.fill").foregroundStyle(accent)
                }
                .font(.system(size: 12, weight: .black).width(.condensed))
                .textCase(.uppercase)
                .tracking(0.8)
                .foregroundStyle(ink)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)

                WatchCalorieGauge(
                    eaten: receiver.snapshot.displayCalories,
                    remaining: receiver.snapshot.displayCaloriesRemaining,
                    usesKilojoules: receiver.snapshot.usesKilojoules,
                    progress: receiver.snapshot.calorieProgress,
                    accent: accent
                )

                HStack(alignment: .top, spacing: 6) {
                    ForEach(receiver.snapshot.displayedHomeNutrients) { nutrient in
                        if nutrient.id == "water" {
                            Button {
                                showsWaterLogger = true
                            } label: {
                                WatchNutrientBar(nutrient: nutrient, accent: accent)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Add water")
                            .accessibilityValue(String(localized: "\(nutrient.displayValue) of \(nutrient.displayGoal)\(nutrient.unit)"))
                            .accessibilityHint("Opens quick water amounts")
                        } else {
                            WatchNutrientBar(nutrient: nutrient, accent: accent)
                        }
                    }
                }
                .padding(.vertical, 5)
                .background(Color(hex: PinkNeoFlash.panelDark))
                .overlay { Rectangle().strokeBorder(ink.opacity(0.42), lineWidth: 1).allowsHitTesting(false) }
            }
            .padding(.horizontal, 4)
            .padding(.bottom, 4)
        }
        .background(Color(hex: PinkNeoFlash.paperDark).ignoresSafeArea())
        .onAppear {
            receiver.refreshFromDisk()
        }
        .sheet(isPresented: $showsWaterLogger) {
            WatchWaterLogView(accent: accent)
                .environmentObject(receiver)
        }
    }
}

private struct WatchWaterLogView: View {
    private struct Preset: Identifiable {
        let milliliters: Int
        let label: String
        var id: Int { milliliters }
    }

    @EnvironmentObject private var receiver: WatchSnapshotReceiver
    @Environment(\.dismiss) private var dismiss
    let accent: Color

    private let presets = [
        Preset(milliliters: 250, label: String(localized: "1 glass")),
        Preset(milliliters: 500, label: String(localized: "2 glasses")),
        Preset(milliliters: 750, label: String(localized: "3 glasses")),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Add water")
                    .font(.system(.headline, weight: .black).width(.condensed))
                    .frame(maxWidth: .infinity, alignment: .leading)

                ForEach(presets) { preset in
                    Button {
                        receiver.logWater(milliliters: preset.milliliters)
                        dismiss()
                    } label: {
                        HStack(spacing: 9) {
                            Image(systemName: "drop.fill")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(accent)

                            VStack(alignment: .leading, spacing: 1) {
                                Text(preset.label)
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                Text("~\(receiver.snapshot.waterDisplayValue(preset.milliliters)) \(receiver.snapshot.waterUnitSymbol)")
                                    .font(.system(.caption2, design: .rounded, weight: .medium))
                                    .foregroundStyle(.secondary)
                            }

                            Spacer(minLength: 2)

                            Image(systemName: "plus")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(accent)
                        }
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                        .background(Color(hex: PinkNeoFlash.panelDark))
                        .overlay {
                            Rectangle().strokeBorder(Color(hex: PinkNeoFlash.inkDark).opacity(0.5), lineWidth: 1)
                                .allowsHitTesting(false)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(String(localized: "Add \(preset.label) of water"))
                    .accessibilityValue("\(receiver.snapshot.waterDisplayValue(preset.milliliters)) \(receiver.snapshot.waterUnitSymbol)")
                }
            }
            .padding(.horizontal, 5)
            .padding(.bottom, 4)
        }
        .background(Color(hex: PinkNeoFlash.paperDark).ignoresSafeArea())
    }
}

/// Scaled-down version of the iPhone Home CalorieGauge — dashed top-semicircle
/// track with the eaten count and remaining readout inside the dome.
private struct WatchCalorieGauge: View {
    let eaten: Int
    let remaining: Int
    /// kJ numbers are about 4x the kcal ones, so they always carry their unit.
    let usesKilojoules: Bool
    let progress: Double
    let accent: Color

    private let diameter: CGFloat = 126
    private let lineWidth: CGFloat = 9

    private var dashedStroke: StrokeStyle {
        StrokeStyle(lineWidth: lineWidth, lineCap: .butt, dash: [2.5, 3.8])
    }

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.5, to: 1.0)
                .stroke(accent.opacity(0.20), style: dashedStroke)
                .padding(lineWidth / 2)

            Circle()
                .trim(from: 0.5, to: 0.5 + 0.5 * progress)
                .stroke(accent, style: dashedStroke)
                .padding(lineWidth / 2)

            VStack(spacing: 0) {
                Text("\(eaten)")
                    .font(.system(size: 32, weight: .black).width(.condensed).monospacedDigit())
                    .foregroundStyle(Color(hex: PinkNeoFlash.inkDark))
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)

                HStack(spacing: 3) {
                    Image(systemName: "flame.fill")
                        .font(.system(size: 9, weight: .semibold))
                    Group {
                        if usesKilojoules {
                            Text("\(remaining) kJ left")
                        } else {
                            Text("\(remaining) left")
                        }
                    }
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                }
                .foregroundStyle(Color(hex: PinkNeoFlash.mutedDark))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            }
            .offset(y: -diameter * 0.16)
        }
        .frame(width: diameter, height: diameter)
        .frame(height: diameter * 0.56, alignment: .top)
        .clipped()
    }
}

/// One nutrient as a vertical fill tube, like the iPhone Home macro bars:
/// value on top, tube in the middle, name + goal beneath.
private struct WatchNutrientBar: View {
    let nutrient: WidgetNutrientValue
    let accent: Color

    private let barWidth: CGFloat = 10
    private let barHeight: CGFloat = 34

    var body: some View {
        VStack(spacing: 4) {
            Text(nutrient.displayValue)
                .font(.system(size: 14, weight: .black).width(.condensed).monospacedDigit())
                .foregroundStyle(Color(hex: PinkNeoFlash.inkDark))
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            ZStack(alignment: .bottom) {
                Capsule()
                    .fill(accent.opacity(0.20))
                    .frame(width: barWidth, height: barHeight)

                if nutrient.progress > 0 {
                    Capsule()
                        .fill(accent)
                        .frame(width: barWidth, height: min(barHeight, max(2, barHeight * nutrient.progress)))
                }
            }

            VStack(spacing: 0) {
                Text(nutrient.label)
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(.primary)
                Text("/\(nutrient.displayGoal)\(nutrient.unit)")
                    .font(.system(size: 8, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.55)
        }
        .frame(maxWidth: .infinity)
    }
}

extension Color {
    init(hex: UInt, opacity: Double = 1.0) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
