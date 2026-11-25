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
    case siriPhrases = "siri_phrases"

    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Logging Method")

    static var caseDisplayRepresentations: [LogFoodMethodAppEnum: DisplayRepresentation] {
        [
            .camera: DisplayRepresentation(title: "Camera + Note", image: .init(systemName: "camera.fill")),
            .photos: DisplayRepresentation(title: "Photos", image: .init(systemName: "photo.on.rectangle")),
            .barcode: DisplayRepresentation(title: "Barcode", image: .init(systemName: "barcode.viewfinder")),
            .text: DisplayRepresentation(title: "Text Input", image: .init(systemName: "character.cursor.ibeam")),
            .voice: DisplayRepresentation(title: "Voice", image: .init(systemName: "mic.fill")),
            .manual: DisplayRepresentation(title: "Manual Entry", image: .init(systemName: "square.and.pencil")),
            .siriPhrases: DisplayRepresentation(title: "Siri Phrases", image: .init(systemName: "waveform.circle.fill")),
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
    var accentHex: UInt = PinkNeoFlash.pink
}

struct LogFoodWidgetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> LogFoodEntry {
        LogFoodEntry(date: Date(), method: .camera)
    }

    func snapshot(for configuration: LogFoodWidgetConfigurationIntent, in context: Context) async -> LogFoodEntry {
        LogFoodEntry(date: Date(), method: configuration.method,
                     accentHex: PinkNeoFlash.snapshotAccent(start: WidgetSnapshot.read()?.themeStartHex))
    }

    func timeline(for configuration: LogFoodWidgetConfigurationIntent, in context: Context) async -> Timeline<LogFoodEntry> {
        let entry = await snapshot(for: configuration, in: context)
        return Timeline(entries: [entry], policy: .never)
    }
}

struct LogFoodWidget: Widget {
    let kind: String = "LogFoodWidget"

    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: kind, intent: LogFoodWidgetConfigurationIntent.self, provider: LogFoodWidgetProvider()) { entry in
            LogFoodWidgetView(method: entry.method, accentHex: entry.accentHex)
                .widgetURL(URL(string: "fudai://log-food?method=\(entry.method.rawValue)"))
                .flashWidgetBackground()
        }
        .configurationDisplayName("Log Food")
        .description("Tap to open Fud AI directly into your chosen logging method.")
        .supportedFamilies([.systemSmall])
    }
}

struct LogFoodWidgetView: View {
    @Environment(\.widgetRenderingMode) private var renderingMode
    let method: LogFoodMethodAppEnum
    var accentHex = PinkNeoFlash.pink

    var body: some View {
        let accent = Color(hex: accentHex)
        VStack(alignment: .leading, spacing: 6) {
            FlashWidgetHeading(title: "Log Food", icon: "plus", accent: accent)
            Spacer(minLength: 0)
            Group {
                if renderingMode == .fullColor {
                    glyph
                        .foregroundStyle(PinkNeoFlash.prefersDarkInk(on: accentHex) ? Color.black : Color.white)
                        .background(accent)
                        .overlay { Rectangle().strokeBorder(WidgetPalette.ink, lineWidth: 1) }
                } else {
                    glyph
                        .overlay { Rectangle().strokeBorder(lineWidth: 1) }
                        .widgetAccentable()
                }
            }
            .accessibilityHidden(true)
            Spacer(minLength: 0)
            Text(title)
                .font(.system(.subheadline, weight: .black).width(.condensed))
                .foregroundStyle(WidgetPalette.ink)
                .lineLimit(2)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }

    private var glyph: some View {
        Image(systemName: iconName)
            .font(.system(size: 23, weight: .bold))
            .frame(width: 46, height: 46)
    }

    private var title: String {
        switch method {
        case .camera: Self.localized("Camera + Note")
        case .photos: Self.localized("Photos")
        case .barcode: Self.localized("Barcode")
        case .text: Self.localized("Text Input")
        case .voice: Self.localized("Voice")
        case .manual: Self.localized("Manual Entry")
        case .siriPhrases: Self.localized("Siri Phrases")
        case .recent: Self.localized("Recent")
        case .frequent: Self.localized("Frequent")
        case .favorites: Self.localized("Favorites")
        case .copyFromDay: Self.localized("Copy from Day")
        }
    }

    private static func localized(_ key: String) -> String {
        NSLocalizedString(key, bundle: .main, comment: "Food logging method")
    }

    private var iconName: String {
        switch method {
        case .camera: "camera.fill"
        case .photos: "photo.on.rectangle"
        case .barcode: "barcode.viewfinder"
        case .text: "character.cursor.ibeam"
        case .voice: "mic.fill"
        case .manual: "square.and.pencil"
        case .siriPhrases: "waveform.circle.fill"
        case .recent: "clock.fill"
        case .frequent: "repeat"
        case .favorites: "heart.fill"
        case .copyFromDay: "calendar"
        }
    }
}
