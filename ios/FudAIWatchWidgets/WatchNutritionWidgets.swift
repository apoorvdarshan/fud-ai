import SwiftUI
import WidgetKit

private enum WatchWidgetPalette {
    static let calories = Color.red
    static let protein = Color.blue
    static let carbs = Color.green
    static let fat = Color.orange
}

private enum WatchWidgetFormat {
    static func macro(_ value: Double) -> String {
        if abs(value.rounded() - value) < 0.0001 {
            return "\(Int(value.rounded()))"
        }
        return String(format: "%.1f", value)
    }
}

struct WatchNutritionEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct WatchNutritionProvider: TimelineProvider {
    func placeholder(in context: Context) -> WatchNutritionEntry {
        WatchNutritionEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (WatchNutritionEntry) -> Void) {
        completion(WatchNutritionEntry(date: Date(), snapshot: WidgetSnapshot.read() ?? .empty))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WatchNutritionEntry>) -> Void) {
        let now = Date()
        let entry = WatchNutritionEntry(date: now, snapshot: WidgetSnapshot.read() ?? .empty)
        let nextRefresh = Calendar.current.date(byAdding: .minute, value: 30, to: now) ?? now.addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
    }
}

struct WatchCaloriesWidget: Widget {
    let kind = "WatchCaloriesWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchNutritionProvider()) { entry in
            WatchCaloriesWidgetView(snapshot: entry.snapshot)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Fud AI Calories")
        .description("Today's calories on your watch face.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

struct WatchProteinWidget: Widget {
    let kind = "WatchProteinWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchNutritionProvider()) { entry in
            WatchProteinWidgetView(snapshot: entry.snapshot)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Fud AI Protein")
        .description("Today's protein on your watch face.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner])
    }
}

struct WatchMacrosWidget: Widget {
    let kind = "WatchMacrosWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WatchNutritionProvider()) { entry in
            WatchMacrosWidgetView(snapshot: entry.snapshot)
                .containerBackground(.clear, for: .widget)
        }
        .configurationDisplayName("Fud AI Macros")
        .description("Protein, carbs, and fat on your watch face.")
        .supportedFamilies([.accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

private struct WatchCaloriesWidgetView: View {
    let snapshot: WidgetSnapshot
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            NutrientCircularView(
                value: "\(snapshot.displayCalories)",
                label: snapshot.energyUnitSymbol,
                progress: snapshot.calorieProgress,
                color: WatchWidgetPalette.calories,
                icon: "flame.fill"
            )
        case .accessoryRectangular:
            CaloriesRectangularView(snapshot: snapshot)
        case .accessoryInline:
            Text(verbatim: "\(snapshot.displayCalories) / \(snapshot.displayCalorieGoal) \(snapshot.energyUnitSymbol)")
        case .accessoryCorner:
            CornerValueView(
                value: "\(snapshot.displayCalories)",
                unit: snapshot.energyUnitSymbol,
                progress: snapshot.calorieProgress,
                color: WatchWidgetPalette.calories
            )
        default:
            NutrientCircularView(
                value: "\(snapshot.displayCalories)",
                label: snapshot.energyUnitSymbol,
                progress: snapshot.calorieProgress,
                color: WatchWidgetPalette.calories,
                icon: "flame.fill"
            )
        }
    }
}

private struct WatchProteinWidgetView: View {
    let snapshot: WidgetSnapshot
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            NutrientCircularView(
                value: WatchWidgetFormat.macro(snapshot.protein),
                label: "prot",
                progress: snapshot.proteinProgress,
                color: WatchWidgetPalette.protein,
                icon: "bolt.fill"
            )
        case .accessoryRectangular:
            NutrientRectangularView(
                title: "Protein",
                value: "\(WatchWidgetFormat.macro(snapshot.protein))g",
                subtitle: "of \(snapshot.proteinGoal)g",
                footer: "\(WatchWidgetFormat.macro(snapshot.proteinRemaining))g left",
                progress: snapshot.proteinProgress,
                color: WatchWidgetPalette.protein,
                icon: "bolt.fill"
            )
        case .accessoryInline:
            Text("\(WatchWidgetFormat.macro(snapshot.protein))g / \(snapshot.proteinGoal)g protein")
        case .accessoryCorner:
            CornerValueView(
                value: WatchWidgetFormat.macro(snapshot.protein),
                unit: "g",
                progress: snapshot.proteinProgress,
                color: WatchWidgetPalette.protein
            )
        default:
            NutrientCircularView(
                value: WatchWidgetFormat.macro(snapshot.protein),
                label: "prot",
                progress: snapshot.proteinProgress,
                color: WatchWidgetPalette.protein,
                icon: "bolt.fill"
            )
        }
    }
}

private struct WatchMacrosWidgetView: View {
    let snapshot: WidgetSnapshot
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular:
            MacrosCircularView(snapshot: snapshot)
        case .accessoryRectangular:
            MacrosRectangularView(snapshot: snapshot)
        case .accessoryInline:
            Text("P\(WatchWidgetFormat.macro(snapshot.protein)) C\(WatchWidgetFormat.macro(snapshot.carbs)) F\(WatchWidgetFormat.macro(snapshot.fat))")
        default:
            MacrosCircularView(snapshot: snapshot)
        }
    }
}

private struct CaloriesRectangularView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        NutrientRectangularView(
            title: "Calories",
            value: "\(snapshot.displayCalories)",
            subtitle: "of \(snapshot.displayCalorieGoal) \(snapshot.energyUnitSymbol)",
            footer: "P\(WatchWidgetFormat.macro(snapshot.protein)) C\(WatchWidgetFormat.macro(snapshot.carbs)) F\(WatchWidgetFormat.macro(snapshot.fat))",
            progress: snapshot.calorieProgress,
            color: WatchWidgetPalette.calories,
            icon: "flame.fill"
        )
    }
}

private struct NutrientCircularView: View {
    let value: String
    let label: String
    let progress: Double
    let color: Color
    let icon: String

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            Circle()
                .stroke(.secondary.opacity(0.28), lineWidth: 3.5)
                .padding(3)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(color, style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(3)
            VStack(spacing: 0) {
                Image(systemName: icon)
                    .font(.system(size: 9, weight: .bold))
                Text(value)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.55)
                    .lineLimit(1)
                Text(label)
                    .font(.system(size: 7, weight: .semibold, design: .rounded))
            }
            .widgetAccentable()
        }
    }
}

private struct NutrientRectangularView: View {
    let title: String
    let value: String
    let subtitle: String
    let footer: String
    let progress: Double
    let color: Color
    let icon: String

    var body: some View {
        HStack(spacing: 8) {
            ZStack {
                Circle()
                    .stroke(.secondary.opacity(0.28), lineWidth: 3)
                Circle()
                    .trim(from: 0, to: progress)
                    .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .bold))
            }
            .frame(width: 36, height: 36)
            .widgetAccentable()

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(value)
                    .font(.system(.headline, design: .rounded, weight: .bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .widgetAccentable()
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text(footer)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
        }
    }
}

private struct CornerValueView: View {
    let value: String
    let unit: String
    let progress: Double
    let color: Color

    var body: some View {
        ZStack {
            Circle()
                .trim(from: 0, to: progress)
                .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .widgetAccentable()
            VStack(spacing: 0) {
                Text(value)
                    .font(.system(size: 12, weight: .bold, design: .rounded))
                    .minimumScaleFactor(0.55)
                    .lineLimit(1)
                Text(unit)
                    .font(.system(size: 7, weight: .semibold, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private struct MacrosCircularView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()
            VStack(spacing: 2) {
                MacroMiniLine(label: "P", value: snapshot.protein, progress: snapshot.proteinProgress, color: WatchWidgetPalette.protein)
                MacroMiniLine(label: "C", value: snapshot.carbs, progress: snapshot.carbsProgress, color: WatchWidgetPalette.carbs)
                MacroMiniLine(label: "F", value: snapshot.fat, progress: snapshot.fatProgress, color: WatchWidgetPalette.fat)
            }
            .padding(.horizontal, 7)
        }
    }
}

private struct MacrosRectangularView: View {
    let snapshot: WidgetSnapshot

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Macros")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            MacroBar(label: "P", value: snapshot.protein, goal: snapshot.proteinGoal, progress: snapshot.proteinProgress, color: WatchWidgetPalette.protein)
            MacroBar(label: "C", value: snapshot.carbs, goal: snapshot.carbsGoal, progress: snapshot.carbsProgress, color: WatchWidgetPalette.carbs)
            MacroBar(label: "F", value: snapshot.fat, goal: snapshot.fatGoal, progress: snapshot.fatProgress, color: WatchWidgetPalette.fat)
        }
    }
}

private struct MacroMiniLine: View {
    let label: String
    let value: Double
    let progress: Double
    let color: Color

    var body: some View {
        HStack(spacing: 2) {
            Text(label)
                .font(.system(size: 8, weight: .bold, design: .rounded))
                .frame(width: 8, alignment: .leading)
            GeometryReader { geometry in
                Capsule()
                    .fill(color.opacity(0.28))
                    .overlay(alignment: .leading) {
                        Capsule()
                            .fill(color)
                            .frame(width: max(3, geometry.size.width * progress))
                    }
            }
            .frame(height: 4)
            Text(WatchWidgetFormat.macro(value))
                .font(.system(size: 8, weight: .semibold, design: .rounded))
                .minimumScaleFactor(0.6)
                .frame(width: 16, alignment: .trailing)
        }
    }
}

private struct MacroBar: View {
    let label: String
    let value: Double
    let goal: Int
    let progress: Double
    let color: Color

    var body: some View {
        HStack(spacing: 4) {
            Text(label)
                .font(.caption2.weight(.bold))
                .frame(width: 10, alignment: .leading)
            GeometryReader { geometry in
                Capsule()
                    .fill(color.opacity(0.24))
                    .overlay(alignment: .leading) {
                        Capsule()
                            .fill(color)
                            .frame(width: max(4, geometry.size.width * progress))
                    }
            }
            .frame(height: 5)
            Text("\(WatchWidgetFormat.macro(value))/\(goal)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: 48, alignment: .trailing)
        }
    }
}
