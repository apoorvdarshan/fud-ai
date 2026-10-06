import SwiftUI
import WidgetKit

/// Match the main app's pink/red theme without importing Theme.swift
/// (which lives in the main app target).
enum WidgetPalette {
    static let calorie = Color(red: 0xFF / 255, green: 0x37 / 255, blue: 0x5F / 255)
    static let calorieLight = Color(red: 0xFF / 255, green: 0x6B / 255, blue: 0x8A / 255)
    static var calorieGradient: LinearGradient {
        LinearGradient(colors: [calorie, calorieLight], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var background: some ShapeStyle {
        Color(.systemBackground)
    }
}

extension Color {
    init(hex: UInt) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// The user's theme gradient synced from the app; Fud Pink when absent.
extension WidgetSnapshot {
    var themeColors: [Color] {
        [Color(hex: themeStartHex ?? 0xFF375F), Color(hex: themeEndHex ?? 0xFF6B8A)]
    }
    var themeColor: Color { themeColors[0] }
    var themeGradient: LinearGradient {
        LinearGradient(colors: themeColors, startPoint: .topLeading, endPoint: .bottomTrailing)
    }
}

/// Dashed top-semicircle speedometer gauge, same design as the app's Home.
/// Shared by the Calorie and Protein widget views.
struct SpeedometerGauge<Center: View>: View {
    let progress: Double
    let colors: [Color]
    let diameter: CGFloat
    let lineWidth: CGFloat
    @ViewBuilder let center: () -> Center

    private var dashedStroke: StrokeStyle {
        StrokeStyle(lineWidth: lineWidth, lineCap: .butt, dash: [3, 4.6])
    }

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0.5, to: 1.0)
                .stroke((colors.first ?? WidgetPalette.calorie).opacity(0.14), style: dashedStroke)
                .padding(lineWidth / 2)

            Circle()
                .trim(from: 0.5, to: 0.5 + 0.5 * progress)
                .stroke(
                    LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing),
                    style: dashedStroke
                )
                .padding(lineWidth / 2)

            center()
                .offset(y: -diameter * 0.13)
        }
        .frame(width: diameter, height: diameter)
        .frame(height: diameter * 0.58, alignment: .top)
        .clipped()
    }
}

/// One nutrient as a vertical fill tube, like the app's Home macro bars:
/// value on top, tube in the middle, name + goal beneath.
struct VerticalNutrientBar: View {
    let nutrient: WidgetNutrientValue
    let colors: [Color]
    let barHeight: CGFloat
    var barWidth: CGFloat = 12
    var valueSize: CGFloat = 15

    var body: some View {
        VStack(spacing: 5) {
            Text(nutrient.displayValue)
                .font(.system(size: valueSize, weight: .bold, design: .rounded).monospacedDigit())
                .foregroundStyle(
                    LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
                )
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            ZStack(alignment: .bottom) {
                Capsule()
                    .fill((colors.first ?? WidgetPalette.calorie).opacity(0.14))
                    .frame(width: barWidth, height: barHeight)

                if nutrient.progress > 0 {
                    Capsule()
                        .fill(LinearGradient(colors: colors, startPoint: .bottom, endPoint: .top))
                        .frame(width: barWidth, height: max(barWidth, barHeight * nutrient.progress))
                }
            }

            VStack(spacing: 0) {
                Text(nutrient.label)
                    .font(.system(size: 10, weight: .semibold, design: .rounded))
                    .foregroundStyle(.primary)
                Text("/\(nutrient.displayGoal)\(nutrient.unit)")
                    .font(.system(size: 9, weight: .medium, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .lineLimit(1)
            .minimumScaleFactor(0.55)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Top-level dispatcher — WidgetKit gives us an `Environment(\.widgetFamily)`.
struct CalorieWidgetView: View {
    let entry: CalorieEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .systemSmall:       SmallCalorieView(snapshot: entry.snapshot)
        case .systemMedium:      MediumCalorieView(snapshot: entry.snapshot)
        case .systemLarge:       LargeCalorieView(snapshot: entry.snapshot)
        case .accessoryCircular: CircularCalorieView(snapshot: entry.snapshot)
        case .accessoryRectangular: RectangularCalorieView(snapshot: entry.snapshot)
        case .accessoryInline:   InlineCalorieView(snapshot: entry.snapshot)
        default:                 SmallCalorieView(snapshot: entry.snapshot)
        }
    }
}

// MARK: - Home Screen

private struct SmallCalorieView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "flame.fill")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(snapshot.themeGradient)
                Text("Today")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
                Spacer()
            }

            Spacer(minLength: 2)

            SpeedometerGauge(
                progress: snapshot.calorieProgress,
                colors: snapshot.themeColors,
                diameter: 118,
                lineWidth: 9
            ) {
                VStack(spacing: 0) {
                    Text("\(snapshot.calories)")
                        .font(.system(.title3, design: .rounded, weight: .bold).monospacedDigit())
                        .foregroundStyle(snapshot.themeGradient)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                        .padding(.horizontal, 16)
                    Text("/ \(snapshot.calorieGoal)")
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 2)

            Text("\(snapshot.caloriesRemaining) kcal left")
                .font(.system(.caption2, design: .rounded, weight: .semibold))
                .foregroundStyle(snapshot.themeColor)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }
}

private struct MediumCalorieView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(spacing: 2) {
                SpeedometerGauge(
                    progress: snapshot.calorieProgress,
                    colors: snapshot.themeColors,
                    diameter: 126,
                    lineWidth: 10
                ) {
                    Text("\(snapshot.calories)")
                        .font(.system(size: 26, weight: .bold, design: .rounded).monospacedDigit())
                        .foregroundStyle(snapshot.themeGradient)
                        .lineLimit(1)
                        .minimumScaleFactor(0.55)
                        .padding(.horizontal, 18)
                }

                HStack(spacing: 4) {
                    Image(systemName: "flame.fill")
                        .font(.system(size: 10, weight: .semibold))
                    Text("\(snapshot.caloriesRemaining) left")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                }
                .foregroundStyle(snapshot.themeColor)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            }
            .frame(width: 128)

            HStack(alignment: .center, spacing: 6) {
                ForEach(snapshot.displayedHomeNutrients) { nutrient in
                    VerticalNutrientBar(
                        nutrient: nutrient,
                        colors: snapshot.themeColors,
                        barHeight: 52,
                        barWidth: 11,
                        valueSize: 14
                    )
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}

private struct LargeCalorieView: View {
    let snapshot: WidgetSnapshot

    private var remainingText: String {
        snapshot.caloriesRemaining > 0
            ? String.localizedStringWithFormat(
                NSLocalizedString("%lld kcal left", bundle: .main, comment: "Calories remaining"),
                Int64(snapshot.caloriesRemaining)
            )
            : NSLocalizedString("Goal reached", bundle: .main, comment: "Calorie goal status")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Today")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Text("Nutrition")
                        .font(.system(.title3, design: .rounded, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }

                Spacer()

                Label("Fud AI", systemImage: "flame.fill")
                    .font(.system(.caption, design: .rounded, weight: .bold))
                    .foregroundStyle(snapshot.themeGradient)
                    .labelStyle(.titleAndIcon)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }

            VStack(spacing: 2) {
                SpeedometerGauge(
                    progress: snapshot.calorieProgress,
                    colors: snapshot.themeColors,
                    diameter: 196,
                    lineWidth: 14
                ) {
                    VStack(spacing: 1) {
                        Text("\(snapshot.calories)")
                            .font(.system(size: 38, weight: .bold, design: .rounded).monospacedDigit())
                            .foregroundStyle(snapshot.themeGradient)
                            .lineLimit(1)
                            .minimumScaleFactor(0.55)
                            .padding(.horizontal, 30)
                        Text("/ \(snapshot.calorieGoal) kcal")
                            .font(.system(.caption2, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                HStack(spacing: 5) {
                    Image(systemName: "flame.fill")
                        .font(.system(size: 11, weight: .semibold))
                    Text(remainingText)
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                }
                .foregroundStyle(snapshot.themeColor)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)

            HStack(alignment: .center, spacing: 10) {
                ForEach(snapshot.displayedHomeNutrients) { nutrient in
                    VerticalNutrientBar(
                        nutrient: nutrient,
                        colors: snapshot.themeColors,
                        barHeight: 84,
                        barWidth: 14,
                        valueSize: 18
                    )
                }
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

// MARK: - Lock Screen

/// Above-the-clock circular — compact value-first display for quick scanning.
private struct CircularCalorieView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        AccessoryCircularMetricView(
            iconName: "flame.fill",
            value: "\(snapshot.calories)",
            label: "kcal"
        )
    }
}

private struct RectangularCalorieView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Header: just the name and today's eaten count, rendered large —
            // no flame icon, no goal (matches the label+eaten style of the grid).
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Text("Calories")
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)

                Spacer(minLength: 4)

                Text("\(snapshot.calories)")
                    .font(.system(size: 17, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .widgetAccentable()
            }

            // All 4 visible Home nutrients as a 2x2 grid — Water occupies slot four when enabled.
            // don't fit the rectangular family's height. Name + eaten amount
            // only; goals are dropped since value-vs-goal pairs read as number
            // soup at this size.
            LazyVGrid(
                columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)],
                alignment: .leading,
                spacing: 3
            ) {
                ForEach(snapshot.displayedHomeNutrients) { nutrient in
                    HStack(spacing: 3) {
                        Text(nutrient.label)
                            .font(.system(size: 11, weight: .semibold, design: .rounded))
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)

                        Spacer(minLength: 2)

                        Text("\(nutrient.displayValue)\(nutrient.unit)")
                            .font(.system(size: 11, weight: .bold, design: .rounded))
                            .monospacedDigit()
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                            .widgetAccentable()
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

private struct InlineCalorieView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        // Inline widgets get exactly one line of text; iOS ignores colors.
        Text("\(snapshot.calories) / \(snapshot.calorieGoal) kcal · \(snapshot.caloriesRemaining) left")
    }
}

struct AccessoryMetricList<Content: View>: View {
    private let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
    }
}

struct AccessoryMetricRow: View {
    let iconName: String
    let label: String
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Image(systemName: iconName)
                .font(.system(size: 11, weight: .semibold))
                .frame(width: 15)
                .widgetAccentable()

            Text(label)
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Spacer(minLength: 4)

            Text(value)
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .widgetAccentable()
        }
    }
}

struct AccessoryCircularMetricView: View {
    let iconName: String
    let value: String
    let label: String

    private var valueFontSize: CGFloat {
        value.count <= 3 ? 20 : 17
    }

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()

            VStack(spacing: 0) {
                Image(systemName: iconName)
                    .font(.system(size: 10, weight: .semibold))
                    .frame(height: 12)

                Text(value)
                    .font(.system(size: valueFontSize, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.55)

                Text(label)
                    .font(.system(size: 8, weight: .semibold, design: .rounded))
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 3)
        }
        .widgetAccentable()
    }
}
