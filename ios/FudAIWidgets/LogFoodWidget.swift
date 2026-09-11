import AppIntents
import WidgetKit
import SwiftUI

enum LogFoodMethodAppEnum: String, AppEnum {
    case camera
    case photos
    case barcode
    case text
    case voice
    case manual
    case recent
    case frequent
    case favorites
    case copyFromDay = "copy_from_day"

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Logging Method")

    static var caseDisplayRepresentations: [LogFoodMethodAppEnum: DisplayRepresentation] {
        [
            .camera: DisplayRepresentation(title: "Camera + Note", image: .init(systemName: "camera.fill")),
            .photos: DisplayRepresentation(title: "Photos", image: .init(systemName: "photo.on.rectangle")),
            .barcode: DisplayRepresentation(title: "Barcode", image: .init(systemName: "barcode.viewfinder")),
            .text: DisplayRepresentation(title: "Text Input", image: .init(systemName: "character.cursor.ibeam")),
            .voice: DisplayRepresentation(title: "Voice", image: .init(systemName: "mic.fill")),
            .manual: DisplayRepresentation(title: "Manual Entry", image: .init(systemName: "square.and.pencil")),
            .recent: DisplayRepresentation(title: "Recent", image: .init(systemName: "clock.fill")),
            .frequent: DisplayRepresentation(title: "Frequent", image: .init(systemName: "repeat")),
            .favorites: DisplayRepresentation(title: "Favorites", image: .init(systemName: "heart.fill")),
            .copyFromDay: DisplayRepresentation(title: "Copy from Day", image: .init(systemName: "calendar")),
        ]
    }
}

struct LogFoodWidgetConfigurationIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "Log Food Widget"
    static var description = IntentDescription("Choose which logging flow opens when you tap the widget.")

    @Parameter(title: "Method", default: .camera)
    var method: LogFoodMethodAppEnum
}

struct LogFoodEntry: TimelineEntry {
    let date: Date
    let method: LogFoodMethodAppEnum
}

struct LogFoodWidgetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> LogFoodEntry {
        LogFoodEntry(date: Date(), method: .camera)
    }

    func snapshot(for configuration: LogFoodWidgetConfigurationIntent, in context: Context) async -> LogFoodEntry {
        LogFoodEntry(date: Date(), method: configuration.method)
    }

    func timeline(for configuration: LogFoodWidgetConfigurationIntent, in context: Context) async -> Timeline<LogFoodEntry> {
        let entry = LogFoodEntry(date: Date(), method: configuration.method)
        return Timeline(entries: [entry], policy: .never)
    }
}

struct LogFoodWidget: Widget {
    let kind: String = "LogFoodWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: LogFoodWidgetConfigurationIntent.self, provider: LogFoodWidgetProvider()) { entry in
            LogFoodWidgetView(method: entry.method)
                .widgetURL(URL(string: "fudai://log-food?method=\(entry.method.rawValue)"))
                .containerBackground(WidgetPalette.background, for: .widget)
        }
        .configurationDisplayName("Log Food")
        .description("Tap to open Fud AI directly into your chosen logging method.")
        .supportedFamilies([.systemSmall])
    }
}

struct LogFoodWidgetView: View {
    let method: LogFoodMethodAppEnum

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: iconName)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(WidgetPalette.calorieGradient)
            Text("Log Food")
                .font(.headline)
                .foregroundStyle(.primary)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(12)
    }

    private var title: String {
        switch method {
        case .camera: "Camera + Note"
        case .photos: "Photos"
        case .barcode: "Barcode"
        case .text: "Text Input"
        case .voice: "Voice"
        case .manual: "Manual Entry"
        case .recent: "Recent"
        case .frequent: "Frequent"
        case .favorites: "Favorites"
        case .copyFromDay: "Copy from Day"
        }
    }

    private var iconName: String {
        switch method {
        case .camera: "camera.fill"
        case .photos: "photo.on.rectangle"
        case .barcode: "barcode.viewfinder"
        case .text: "character.cursor.ibeam"
        case .voice: "mic.fill"
        case .manual: "square.and.pencil"
        case .recent: "clock.fill"
        case .frequent: "repeat"
        case .favorites: "heart.fill"
        case .copyFromDay: "calendar"
        }
    }
}
