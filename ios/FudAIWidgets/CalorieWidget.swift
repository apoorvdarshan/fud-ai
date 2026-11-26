import WidgetKit
import SwiftUI

/// WidgetKit entry holding a single WidgetSnapshot.
struct CalorieEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct CalorieProvider: TimelineProvider {
    func placeholder(in context: Context) -> CalorieEntry {
        CalorieEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (CalorieEntry) -> Void) {
        let snap = WidgetSnapshot.read() ?? .empty
        completion(CalorieEntry(date: Date(), snapshot: snap))
    }

    /// Timeline with the current snapshot + a refresh hint at the next hour
    /// so widgets that weren't manually reloaded still roll over when the day ends.
    func getTimeline(in context: Context, completion: @escaping (Timeline<CalorieEntry>) -> Void) {
        let now = Date()
        let snap = WidgetSnapshot.read() ?? .empty
        let entry = CalorieEntry(date: now, snapshot: snap)
        let nextRefresh = Calendar.current.date(byAdding: .minute, value: 30, to: now) ?? now.addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
    }
}

struct CalorieWidget: Widget {
    let kind: String = "CalorieWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: CalorieProvider()) { entry in
            CalorieWidgetView(entry: entry)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Fud AI")
        .description("See today's calories and macros at a glance.")
        .supportedFamilies([
            .systemSmall,
            .systemMedium,
            .systemLarge,
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline,
        ])
    }
}
