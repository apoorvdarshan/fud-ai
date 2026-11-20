import AppIntents
import Foundation
import UniformTypeIdentifiers

struct ExportFoodDiaryIntent: AppIntent {
    static let title: LocalizedStringResource = "Export Food Diary"
    static let description = IntentDescription(
        "Create a JSON file of your food and water logs for the selected dates. Fud AI does not upload the file.",
        categoryName: "Nutrition"
    )
    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @Parameter(title: "From")
    var startDate: Date

    @Parameter(title: "To")
    var endDate: Date

    static var parameterSummary: some ParameterSummary {
        Summary("Export food diary from \(\.$startDate) to \(\.$endDate)")
    }

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<IntentFile> {
        let food = FoodStore(observesExternalChanges: false)
        let water = WaterStore()
        if food.isPersistenceBlocked || water.isPersistenceBlocked {
            throw ExportDiaryError.unreadableData
        }
        guard let export = DiaryExporter.build(
            from: startDate, to: endDate, format: .json, foodStore: food,
            profile: UserProfile.load() ?? .default, waterEntries: water.entries
        ) else {
            throw ExportDiaryError.noData
        }
        return .result(value: IntentFile(data: export.data, filename: export.filename, type: .json))
    }

    private enum ExportDiaryError: LocalizedError {
        case noData
        case unreadableData

        var errorDescription: String? {
            switch self {
            case .noData:
                return String(localized: "There are no food or water entries in the selected range.")
            case .unreadableData:
                return String(localized: "Some diary data could not be read. Open Fud AI to recover it before exporting.")
            }
        }
    }
}
