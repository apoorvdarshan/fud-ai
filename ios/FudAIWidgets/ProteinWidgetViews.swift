import SwiftUI
import WidgetKit

struct ProteinWidgetView: View {
    let entry: ProteinEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .systemSmall:          SmallProteinView(snapshot: entry.snapshot)
        case .systemMedium:         MediumProteinView(snapshot: entry.snapshot)
        case .accessoryCircular:    CircularProteinView(snapshot: entry.snapshot)
        case .accessoryRectangular: RectangularProteinView(snapshot: entry.snapshot)
        case .accessoryInline:      InlineProteinView(snapshot: entry.snapshot)
        default:                    SmallProteinView(snapshot: entry.snapshot)
        }
    }
}

// MARK: - Home Screen

private struct SmallProteinView: View {
    let snapshot: WidgetSnapshot
    private var nutrient: WidgetNutrientValue { snapshot.primaryHomeNutrient }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: nutrient.iconName)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundStyle(snapshot.themeGradient)
                Text(nutrient.label)
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
                Spacer()
            }

            Spacer(minLength: 2)

            SpeedometerGauge(
                progress: nutrient.progress,
                colors: snapshot.themeColors,
                diameter: 118,
                lineWidth: 9
            ) {
                VStack(spacing: 0) {
                    Text(nutrient.displayCurrentWithUnit)
                        .font(.system(.title3, design: .rounded, weight: .bold).monospacedDigit())
                        .foregroundStyle(snapshot.themeGradient)
                        .minimumScaleFactor(0.6)
                        .lineLimit(1)
                        .padding(.horizontal, 16)
                    Text("/ \(nutrient.displayGoalWithUnit)")
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)

            Spacer(minLength: 2)

            Text(nutrient.displayRemaining)
                .font(.system(.caption2, design: .rounded, weight: .semibold))
                .foregroundStyle(snapshot.themeColor)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
    }
}

private struct MediumProteinView: View {
    let snapshot: WidgetSnapshot
    private var nutrient: WidgetNutrientValue { snapshot.primaryHomeNutrient }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(spacing: 2) {
                SpeedometerGauge(
                    progress: nutrient.progress,
                    colors: snapshot.themeColors,
                    diameter: 126,
                    lineWidth: 10
                ) {
                    VStack(spacing: 0) {
                        Text(nutrient.displayValue)
                            .font(.system(size: 24, weight: .bold, design: .rounded).monospacedDigit())
                            .foregroundStyle(snapshot.themeGradient)
                            .lineLimit(1)
                            .minimumScaleFactor(0.55)
                            .padding(.horizontal, 18)
                        Text("/ \(nutrient.displayGoalWithUnit)")
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Text(nutrient.displayRemaining)
                    .font(.system(.caption, design: .rounded, weight: .semibold))
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

// MARK: - Lock Screen

private struct CircularProteinView: View {
    let snapshot: WidgetSnapshot
    private var nutrient: WidgetNutrientValue { snapshot.primaryHomeNutrient }

    var body: some View {
        AccessoryCircularMetricView(
            iconName: nutrient.lockScreenIconName,
            value: nutrient.displayValue,
            label: nutrient.shortLabel
        )
    }
}

private struct RectangularProteinView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        AccessoryMetricList {
            ForEach(snapshot.displayedHomeNutrients) { nutrient in
                AccessoryMetricRow(
                    iconName: nutrient.iconName,
                    label: nutrient.label,
                    value: nutrient.displayPair
                )
            }
        }
    }
}

private struct InlineProteinView: View {
    let snapshot: WidgetSnapshot
    private var nutrient: WidgetNutrientValue { snapshot.primaryHomeNutrient }

    var body: some View {
        Text("\(nutrient.displayPair) \(nutrient.label.lowercased()) · \(nutrient.displayRemaining)")
    }
}
