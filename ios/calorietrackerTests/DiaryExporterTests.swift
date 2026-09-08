import Foundation
import Testing
@testable import calorietracker

struct DiaryExporterTests {
    @Test func waterOnlyDaysRoundTripAndExportWithoutCalories() throws {
        let day = Calendar.current.date(from: DateComponents(year: 2026, month: 9, day: 1, hour: 10, minute: 15))!
        let water = WaterEntry(date: day, milliliters: 250)
        func export(_ format: DiaryExportFormat) throws -> Data {
            try #require(DiaryExporter.build(from: day, to: day, format: format,
                entries: [], profile: makeProfile(), waterEntries: [water])).data
        }
        let json = try export(.json)
        let root = try #require(JSONSerialization.jsonObject(with: json) as? [String: Any])
        let days = try #require(root["days"] as? [[String: Any]])
        #expect(days[0]["water_total_ml"] as? Int == 250)
        #expect((days[0]["totals"] as? [String: Any])?["calories"] as? Int == 0)
        let preview = try DiaryImporter.parse(json)
        #expect(preview.entries.isEmpty)
        #expect(preview.waterEntries == [water])
        #expect(String(decoding: try export(.markdown), as: UTF8.self).contains("| 10:15 | 250 |"))
        let csv = String(decoding: try export(.csv), as: UTF8.self).split(separator: "\n")
            .map { $0.split(separator: ",", omittingEmptySubsequences: false) }
        #expect(csv.count == 3)
        #expect(csv.allSatisfy { $0.count == csv[0].count })
        #expect(csv[1][35] == "water")
        #expect(csv[1][36] == "250")
        #expect(csv[2][35] == "water_total")
        #expect(csv[2][37] == "250")
        #expect(DiaryImporter.applyingWater(preview, to: [water], mode: .replaceDateRange) == [water])
        var emptyWater = preview
        emptyWater.waterEntries = []
        #expect(DiaryImporter.applyingWater(emptyWater, to: [water], mode: .replaceDateRange).isEmpty)
        let added = DiaryImporter.applyingWater(preview, to: [water], mode: .addAsNew)
        #expect(Set(added.map(\.id)).count == 2)
        let legacy = DiaryImportPreview(entries: [], startDate: preview.startDate, endDate: preview.endDate)
        #expect(DiaryImporter.applyingWater(legacy, to: [water], mode: .replaceDateRange) == [water])
        let outside = WaterEntry(id: water.id, date: day.addingTimeInterval(-86400), milliliters: 500)
        let replaced = DiaryImporter.applyingWater(preview, to: [outside], mode: .replaceDateRange)
        #expect(replaced.first == outside)
        #expect(Set(replaced.map(\.id)).count == 2)
        let invalid = String(decoding: json, as: UTF8.self).replacingOccurrences(of: "\"milliliters\" : 250", with: "\"milliliters\" : -1")
        #expect(throws: DiaryImportError.self) { try DiaryImporter.parse(Data(invalid.utf8)) }
        let invalidTime = String(decoding: json, as: UTF8.self).replacingOccurrences(of: "10:15", with: "25:15")
        #expect(throws: DiaryImportError.self) { try DiaryImporter.parse(Data(invalidTime.utf8)) }
    }

    @Test func mixedDiaryFiltersWaterAndPreservesNutrition() throws {
        let fixture = makeFixture()
        let date = fixture.date
        let water = WaterEntry(date: date, milliliters: 500)
        let previous = Calendar.current.date(byAdding: .day, value: -1, to: date)!
        let outside = WaterEntry(date: previous, milliliters: 250)
        let export = try #require(DiaryExporter.build(from: date, to: date, format: .json,
            entries: [fixture.entry], profile: makeProfile(), waterEntries: [outside, water]))
        let preview = try DiaryImporter.parse(export.data)
        #expect(preview.entries.count == 1)
        #expect(preview.entries[0].calories == fixture.entry.calories)
        #expect(preview.waterEntries.map(\.milliliters) == [500])
        let suite = "DiaryWaterTests-\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = FoodStore(observesExternalChanges: false, defaults: defaults)
        let range = DiaryExporter.resolve(.allTime, customStart: date, customEnd: date,
            foodStore: store, waterEntries: [outside, water])
        #expect(range.0 == Calendar.current.startOfDay(for: previous))
    }

    private let nutrientFields = [
        "sugar_g", "added_sugar_g", "fiber_g", "saturated_fat_g",
        "monounsaturated_fat_g", "polyunsaturated_fat_g", "cholesterol_mg",
        "sodium_mg", "potassium_mg", "trans_fat_g", "calcium_mg", "iron_mg",
        "magnesium_mg", "zinc_mg", "vitamin_a_mcg", "vitamin_c_mg",
        "vitamin_d_mcg", "vitamin_b12_mcg", "vitamin_e_mg", "vitamin_k_mcg",
        "folate_mcg", "omega3_g"
    ]

    @Test func jsonIncludesEveryStoredNutrient() throws {
        let fixture = makeFixture()
        let export = try #require(DiaryExporter.build(
            from: fixture.date,
            to: fixture.date,
            format: .json,
            entries: [fixture.entry],
            profile: makeProfile()
        ))

        let root = try #require(JSONSerialization.jsonObject(with: export.data) as? [String: Any])
        let metadata = try #require(root["export"] as? [String: Any])
        #expect(metadata["format_version"] as? String == "1.5")
        let days = try #require(root["days"] as? [[String: Any]])
        let meals = try #require(days.first?["meals"] as? [[String: Any]])
        let items = try #require(meals.first?["items"] as? [[String: Any]])
        let item = try #require(items.first)

        #expect(item["entry_id"] as? String == fixture.entry.id.uuidString)
        for field in nutrientFields {
            #expect(item[field] != nil, "Missing JSON nutrient field: \(field)")
        }
        #expect(item["fiber_g"] as? Double == 3.3)
        #expect(item["sodium_mg"] as? Double == 8.8)
        #expect(item["vitamin_b12_mcg"] as? Double == 18.8)
        let ingredients = try #require(item["ingredients"] as? [[String: Any]])
        #expect(ingredients.first?["name"] as? String == "Rice")
    }

    @Test func csvAndMarkdownIncludeEveryStoredNutrient() throws {
        let fixture = makeFixture()
        let csvExport = try #require(DiaryExporter.build(
            from: fixture.date,
            to: fixture.date,
            format: .csv,
            entries: [fixture.entry],
            profile: makeProfile()
        ))
        let csv = String(decoding: csvExport.data, as: UTF8.self)
        let lines = csv.split(separator: "\n").map(String.init)
        let headers = try #require(lines.first?.split(separator: ",").map(String.init))
        let values = try #require(lines.dropFirst().first?.split(separator: ",", omittingEmptySubsequences: false).map(String.init))
        #expect(headers.count == values.count)
        for field in nutrientFields {
            #expect(headers.contains(field), "Missing CSV nutrient column: \(field)")
        }
        let fiberIndex = try #require(headers.firstIndex(of: "fiber_g"))
        #expect(values[fiberIndex] == "3.3")
        #expect(csv.contains("Rice"))

        let markdownExport = try #require(DiaryExporter.build(
            from: fixture.date,
            to: fixture.date,
            format: .markdown,
            entries: [fixture.entry],
            profile: makeProfile()
        ))
        let markdown = String(decoding: markdownExport.data, as: UTF8.self)
        for heading in ["Fiber (g)", "Sodium (mg)", "Vitamin A (mcg)", "Vitamin B12 (mcg)", "Omega-3 (g)"] {
            #expect(markdown.contains(heading), "Missing Markdown nutrient heading: \(heading)")
        }
        #expect(markdown.contains("Rice"))
    }

    private func makeFixture() -> (date: Date, entry: FoodEntry) {
        let date = Date(timeIntervalSince1970: 1_752_840_000)
        let entry = FoodEntry(
            name: "Nutrient fixture",
            calories: 120,
            protein: 4.4,
            carbs: 5.5,
            fat: 6.6,
            timestamp: date,
            source: .manual,
            mealType: .lunch,
            sugar: 1.1,
            addedSugar: 2.2,
            fiber: 3.3,
            saturatedFat: 4.4,
            monounsaturatedFat: 5.5,
            polyunsaturatedFat: 6.6,
            cholesterol: 7.7,
            sodium: 8.8,
            potassium: 9.9,
            transFat: 10.1,
            calcium: 11.1,
            iron: 12.2,
            magnesium: 13.3,
            zinc: 14.4,
            vitaminA: 15.5,
            vitaminC: 16.6,
            vitaminD: 17.7,
            vitaminB12: 18.8,
            vitaminE: 19.9,
            vitaminK: 20.1,
            folate: 21.2,
            omega3: 22.3,
            servingSizeGrams: 100,
            ingredients: [MealIngredient(name: "Rice", grams: 100, calories: 120, protein: 4.4, carbs: 5.5, fat: 6.6)]
        )
        return (date, entry)
    }

    private func makeProfile() -> UserProfile {
        UserProfile(
            name: "Exporter",
            gender: .male,
            birthday: Date(timeIntervalSince1970: 0),
            heightCm: 175,
            weightKg: 70,
            activityLevel: .moderate,
            goal: .maintain,
            bodyFatPercentage: nil,
            goalBodyFatPercentage: nil,
            useBodyFatInBMR: nil,
            weeklyChangeKg: nil,
            goalWeightKg: nil,
            customCalories: 2_000,
            customProtein: 120,
            customFat: 60,
            customCarbs: 200,
            autoBalanceMacro: nil
        )
    }
}
