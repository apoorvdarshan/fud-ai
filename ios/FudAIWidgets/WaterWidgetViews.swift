import SwiftUI
import WidgetKit

struct WaterWidgetView: View {
    let entry: WaterEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .systemSmall:
            SmallWaterView(snapshot: entry.snapshot)
        case .accessoryCircular:
            CircularWaterView(snapshot: entry.snapshot)
        case .accessoryRectangular:
            RectangularWaterView(snapshot: entry.snapshot)
        case .accessoryInline:
            InlineWaterView(snapshot: entry.snapshot)
        default:
            SmallWaterView(snapshot: entry.snapshot)
        }
    }
}

private struct SmallWaterView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        if snapshot.waterIsEnabled {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Image(systemName: "drop.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(snapshot.themeGradient)
                    Text("Water")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                }

                Spacer(minLength: 2)

                SpeedometerGauge(
                    progress: snapshot.waterProgress,
                    colors: snapshot.themeColors,
                    diameter: 118,
                    lineWidth: 9
                ) {
                    VStack(spacing: 0) {
                        Text("\(snapshot.waterDisplayValue(snapshot.waterCurrent)) \(snapshot.waterUnitSymbol)")
                            .font(.system(.title3, design: .rounded, weight: .bold).monospacedDigit())
                            .foregroundStyle(snapshot.themeGradient)
                            .minimumScaleFactor(0.5)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                        Text("/ \(snapshot.waterDisplayValue(snapshot.waterGoal)) \(snapshot.waterUnitSymbol)")
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .minimumScaleFactor(0.7)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity)

                Spacer(minLength: 2)

                Text("\(snapshot.waterDisplayValue(snapshot.waterRemaining)) \(snapshot.waterUnitSymbol) left")
                    .font(.system(.caption2, design: .rounded, weight: .semibold))
                    .foregroundStyle(snapshot.themeColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
        } else {
            DisabledWaterView()
        }
    }
}

private struct DisabledWaterView: View {
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "drop.fill")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(WidgetPalette.calorieGradient)
            Text("Water Tracking")
                .font(.system(.headline, design: .rounded, weight: .bold))
            Text("Enable in Fud AI")
                .font(.system(.caption, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct CircularWaterView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        if snapshot.waterIsEnabled {
            Gauge(value: snapshot.waterProgress) {
                Image(systemName: "drop.fill")
            } currentValueLabel: {
                Text("\(Int((snapshot.waterProgress * 100).rounded()))%")
                    .font(.system(size: 14, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.6)
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .widgetAccentable()
        } else {
            AccessoryCircularMetricView(iconName: "drop.fill", value: "—", label: String(localized: "Water"))
        }
    }
}

private struct RectangularWaterView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        if snapshot.waterIsEnabled {
            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 5) {
                    Image(systemName: "drop.fill")
                        .widgetAccentable()
                    Text("Water")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                    Spacer(minLength: 4)
                    Text("\(snapshot.waterDisplayValue(snapshot.waterCurrent)) / \(snapshot.waterDisplayValue(snapshot.waterGoal)) \(snapshot.waterUnitSymbol)")
                        .font(.system(.caption, design: .rounded, weight: .bold).monospacedDigit())
                        .minimumScaleFactor(0.65)
                        .lineLimit(1)
                }
                ProgressView(value: snapshot.waterProgress)
                    .widgetAccentable()
                Text("\(snapshot.waterDisplayValue(snapshot.waterRemaining)) \(snapshot.waterUnitSymbol) left")
                    .font(.system(.caption2, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Label("Water Tracking", systemImage: "drop.fill")
                    .font(.system(.headline, design: .rounded, weight: .semibold))
                    .widgetAccentable()
                Text("Enable Water Tracking in Fud AI")
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct InlineWaterView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        if snapshot.waterIsEnabled {
            Text("Water \(snapshot.waterDisplayValue(snapshot.waterCurrent)) / \(snapshot.waterDisplayValue(snapshot.waterGoal)) \(snapshot.waterUnitSymbol)")
        } else {
            Text("Enable Water Tracking in Fud AI")
        }
    }
}
