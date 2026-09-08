import Foundation
import Testing
@testable import calorietracker

struct DiaryImporterTests {
    @Test func roundTripReplacesRangeAndPreservesMedia() throws {
        let date = try #require(Calendar.current.date(from: DateComponents(year: 2026, month: 8, day: 5, hour: 12, minute: 30)))
        let id = UUID()
        let existing = FoodEntry(
            id: id,
            name: "Rice bowl",
            calories: 500,
            protein: 20,
            carbs: 70,
            fat: 12,
            timestamp: date,
            imageFilename: "meal.jpg",
            source: .textInput,
            mealType: .lunch,
            fiber: 2
        )
        let outside = FoodEntry(
            name: "Earlier meal",
            calories: 100,
            protein: 1,
            carbs: 2,
            fat: 3,
            timestamp: Calendar.current.date(byAdding: .day, value: -3, to: date)!,
            source: .manual,
            mealType: .breakfast
        )
        let profile = UserProfile(
            name: "Importer",
            gender: .male,
            birthday: Date(timeIntervalSince1970: 0),
            heightCm: 175,
            weightKg: 70,
            activityLevel: .moderate,
            goal: .maintain,
            customCalories: 2_000,
            customProtein: 120,
            customFat: 60,
            customCarbs: 200
        )
        let export = try #require(DiaryExporter.build(
            from: date,
            to: date,
            format: .json,
            entries: [existing],
            profile: profile
        ))
        let mutable = try #require(JSONSerialization.jsonObject(with: export.data, options: [.mutableContainers]) as? NSMutableDictionary)
        let days = try #require(mutable["days"] as? NSMutableArray)
        let day = try #require(days.firstObject as? NSMutableDictionary)
        let meals = try #require(day["meals"] as? NSMutableArray)
        let meal = try #require(meals.firstObject as? NSMutableDictionary)
        let items = try #require(meal["items"] as? NSMutableArray)
        let item = try #require(items.firstObject as? NSMutableDictionary)
        item["fiber_g"] = 8.5
        let edited = try JSONSerialization.data(withJSONObject: mutable)

        let preview = try DiaryImporter.parse(edited)
        let result = DiaryImporter.applying(preview, to: [outside, existing], mode: .replaceDateRange)

        #expect(result.count == 2)
        #expect(result.contains { $0.id == outside.id })
        let imported = try #require(result.first { $0.id == id })
        #expect(imported.fiber == 8.5)
        #expect(imported.imageFilename == "meal.jpg")
    }

    @Test func legacyFoodDiaryPreservesWater() throws {
        let preview = try DiaryImporter.parse(Data(validDiary.utf8))
        let water = WaterEntry(date: preview.startDate, milliliters: 250)
        #expect(!preview.includesWater)
        #expect(DiaryImporter.applyingWater(preview, to: [water], mode: .replaceDateRange) == [water])
    }

    @Test func addModeAlwaysCreatesANewIdentity() throws {
        let data = Data(validDiary.utf8)
        let preview = try DiaryImporter.parse(data)
        let result = DiaryImporter.applying(preview, to: preview.entries, mode: .addAsNew)

        #expect(result.count == 2)
        #expect(result[0].id != result[1].id)
    }

    private var validDiary: String {
        """
        {
          "export": {
            "app": "Fud AI",
            "format_version": "1.3",
            "date_range": { "start": "2026-08-05", "end": "2026-08-05" }
          },
          "days": [{
            "date": "2026-08-05",
            "meals": [{
              "type": "lunch",
              "items": [{
                "name": "Rice bowl",
                "quantity_g": 250,
                "calories": 500,
                "protein_g": 20,
                "carbs_g": 70,
                "fat_g": 12,
                "time": "12:30",
                "source": "ai_estimated",
                "ingredients": []
              }]
            }]
          }]
        }
        """
    }
}
