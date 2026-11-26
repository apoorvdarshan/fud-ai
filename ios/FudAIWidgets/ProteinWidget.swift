import WidgetKit
import SwiftUI

struct ProteinEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
}

struct ProteinProvider: TimelineProvider {
    func placeholder(in context: Context) -> ProteinEntry {
        ProteinEntry(date: Date(), snapshot: .placeholder)
    }

    func getSnapshot(in context: Context, completion: @escaping (ProteinEntry) -> Void) {
        let snap = WidgetSnapshot.read() ?? .empty
        completion(ProteinEntry(date: Date(), snapshot: snap))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<ProteinEntry>) -> Void) {
        let now = Date()
        let snap = WidgetSnapshot.read() ?? .empty
        let entry = ProteinEntry(date: now, snapshot: snap)
        let nextRefresh = Calendar.current.date(byAdding: .minute, value: 30, to: now) ?? now.addingTimeInterval(1800)
        completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
    }
}

struct ProteinWidget: Widget {
    let kind: String = "ProteinWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: ProteinProvider()) { entry in
            ProteinWidgetView(entry: entry)
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Fud AI Protein")
        .description("See today's protein progress at a glance.")
        .supportedFamilies([
            .systemSmall,
            .accessoryCircular,
            .accessoryInline,
        ])
    }
}
