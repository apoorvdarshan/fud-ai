import WidgetKit
import SwiftUI

struct WaterEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct WaterProvider: TimelineProvider {
    func placeholder(in context: Context) -> WaterEntry {
        WaterEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (WaterEntry) -> Void) {
        completion(WaterEntry(date: Date(), snapshot: WidgetSnapshot.read() ?? .empty))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WaterEntry>) -> Void) {
        let now = Date()
        let snapshot = WidgetSnapshot.read() ?? .empty
        let nextRefresh = Calendar.current.date(byAdding: .minute, value: 30, to: now)
            ?? now.addingTimeInterval(1_800)
        completion(Timeline(entries: [WaterEntry(date: now, snapshot: snapshot)], policy: .after(nextRefresh)))
    }
}

struct WaterWidget: Widget {
    let kind = "WaterWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: WaterProvider()) { entry in
            WaterWidgetView(entry: entry)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Fud AI Water")
        .description("See today's water progress at a glance.")
        .supportedFamilies([
            .systemSmall,
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline,
        ])
    }
}
