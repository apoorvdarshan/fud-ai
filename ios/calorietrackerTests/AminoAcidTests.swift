import Foundation
import Testing
@testable import calorietracker

@MainActor
struct AminoAcidTests {
    @Test func validEstimatesAreStoredWithTheOtherExtraNutrients() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: """
        {"name":"Chicken breast","calories":165,"protein":31,"carbs":0,"fat":3.6,"serving_size_grams":100,
         "creatine":0.4,"histidine":1.2,"isoleucine":"1.6","leucine":2.5,"lysine":2.7,"methionine":0.9,
         "phenylalanine":1.2,"threonine":1.3,"tryptophan":0.35,"valine":1.5,"unit_options":[]}
        """)

        #expect(analysis.supplementalNutrients["creatine"] == 0.4)
        #expect(analysis.supplementalNutrients[AminoAcid.leucine.storageKey] == 2.5)
        #expect(analysis.supplementalNutrients[AminoAcid.isoleucine.storageKey] == 1.6)
        #expect(analysis.supplementalNutrients[AminoAcid.tryptophan.storageKey] == 0.35)
        #expect(AminoAcid.allCases.allSatisfy { analysis.supplementalNutrients[$0.storageKey] != nil })
    }

    @Test func invalidEstimatesAreDroppedWithoutFailingTheAnalysis() throws {
        let analysis = try GeminiService.parseFoodAnalysis(from: """
        {"name":"Lentil soup","calories":230,"protein":18,"carbs":40,"fat":1,"serving_size_grams":250,
         "histidine":-0.5,"isoleucine":"lots","leucine":true,"lysine":null,"methionine":[0.2],
         "phenylalanine":300,"threonine":0.6,"tryptophan":0,"unit_options":[]}
        """)

        #expect(analysis.name == "Lentil soup")
        #expect(analysis.calories == 230)
        let aminoKeys = AminoAcid.allCases.filter { analysis.supplementalNutrients[$0.storageKey] != nil }
        // Negative, non-numeric, boolean, null, array, more than the 250 g food and missing values are gone.
        #expect(aminoKeys == [.threonine, .tryptophan])
        #expect(analysis.supplementalNutrients[AminoAcid.tryptophan.storageKey] == 0)
    }

    @Test func responsesWithoutAminoAcidsAddNone() throws {
        let analysis = try GeminiService.parseFoodAnalysis(
            from: #"{"name":"Apple","calories":95,"protein":0.5,"carbs":25,"fat":0.3,"serving_size_grams":180,"unit_options":[]}"#
        )
        #expect(analysis.supplementalNutrients.isEmpty)
    }

    @Test func labelEstimatesArePerHundredGramsAndScaleToTheServing() throws {
        let label = try GeminiService.parseNutritionLabel(from: """
        {"name":"Protein bar","calories_per_100g":380,"protein_per_100g":30,"carbs_per_100g":40,"fat_per_100g":12,
         "serving_size_grams":60,"leucine_per_100g":2.4,"lysine_per_100g":-1,"valine_per_100g":150,"unit_options":[]}
        """)

        #expect(label.supplementalNutrientsPer100g == [AminoAcid.leucine.storageKey: 2.4])
        let serving = label.scaled(to: 250)
        #expect(serving.supplementalNutrients[AminoAcid.leucine.storageKey] == 6)
    }

    @Test func servingChangesScaleAminoAcidsLikeOtherNutrients() {
        let snapshot = MealMicronutrientSnapshot(
            fiber: 2,
            supplementalNutrients: [AminoAcid.leucine.storageKey: 1.5, AminoAcid.lysine.storageKey: 0.8]
        )
        let doubled = snapshot.stretched(oldGrams: 150, newGrams: 300)

        #expect(doubled.fiber == 4)
        #expect(doubled.supplementalNutrients[AminoAcid.leucine.storageKey] == 3)
        #expect(doubled.supplementalNutrients[AminoAcid.lysine.storageKey] == 1.6)
    }

    @Test func reviewAndEditSavesKeepSmallAminoAcidEstimates() {
        // A medium banana: these all rounded to 0.0 or 0.1 when every extra used 0.1 g.
        let analyzed: [String: Double] = [
            AminoAcid.tryptophan.storageKey: 0.04,
            AminoAcid.methionine.storageKey: 0.011,
            AminoAcid.histidine.storageKey: 0.09,
            AminoAcid.valine.storageKey: 0.0564,
            "creatine": 0.44,
        ]

        let saved = AminoAcidEstimates.roundedSupplementalNutrients(analyzed)
        #expect(saved[AminoAcid.tryptophan.storageKey] == 0.04)
        #expect(saved[AminoAcid.methionine.storageKey] == 0.011)
        #expect(saved[AminoAcid.histidine.storageKey] == 0.09)
        #expect(saved[AminoAcid.valine.storageKey] == 0.056)
        #expect(saved["creatine"] == 0.4)

        let halfServing = AminoAcidEstimates.roundedSupplementalNutrients(analyzed, scaledBy: 0.5)
        #expect(halfServing[AminoAcid.tryptophan.storageKey] == 0.02)
        #expect(halfServing[AminoAcid.valine.storageKey] == 0.028)
        #expect(halfServing["creatine"] == 0.2)
    }

    @Test func voiceLoggedEntriesKeepSmallAminoAcidEstimates() {
        var analysis = GeminiService.FoodAnalysis(name: "Banana", calories: 105, protein: 1.3, carbs: 27, fat: 0.4, servingSizeGrams: 118)
        analysis.supplementalNutrients = [AminoAcid.tryptophan.storageKey: 0.04, AminoAcid.methionine.storageKey: 0.011]

        let entry = VoiceAssistantFoodBuilder.entry(
            from: analysis, images: [], source: .textInput, servings: 1, timestamp: Date(), mealType: .snack
        )
        #expect(entry.supplementalNutrients[AminoAcid.tryptophan.storageKey] == 0.04)
        #expect(entry.supplementalNutrients[AminoAcid.methionine.storageKey] == 0.011)

        let doubled = entry.voiceAssistantCopy(factor: 2)
        #expect(doubled.supplementalNutrients[AminoAcid.tryptophan.storageKey] == 0.08)
        #expect(doubled.supplementalNutrients[AminoAcid.methionine.storageKey] == 0.022)
    }

    @Test func labelServingsAndExportsKeepSmallAminoAcidEstimates() throws {
        let label = try GeminiService.parseNutritionLabel(from: """
        {"name":"Banana chips","calories_per_100g":520,"protein_per_100g":2.3,"carbs_per_100g":58,"fat_per_100g":34,
         "serving_size_grams":30,"tryptophan_per_100g":0.011,"unit_options":[]}
        """)
        #expect(label.scaled(to: 118).supplementalNutrients[AminoAcid.tryptophan.storageKey] == 0.013)

        let date = Date(timeIntervalSince1970: 1_752_840_000)
        let entry = FoodEntry(
            name: "Banana", calories: 105, protein: 1.3, carbs: 27, fat: 0.4, timestamp: date,
            source: .textInput, mealType: .snack,
            supplementalNutrients: [AminoAcid.tryptophan.storageKey: 0.011, "creatine": 5.04]
        )
        let export = try #require(DiaryExporter.build(
            from: date, to: date, format: .json, entries: [entry], profile: UserProfile.default
        ))
        let root = try #require(JSONSerialization.jsonObject(with: export.data) as? [String: Any])
        let days = try #require(root["days"] as? [[String: Any]])
        let meals = try #require(days.first?["meals"] as? [[String: Any]])
        let items = try #require(meals.first?["items"] as? [[String: Any]])
        let extras = try #require(items.first?["supplemental_nutrients_g"] as? [String: Double])
        #expect(extras[AminoAcid.tryptophan.storageKey] == 0.011)
        #expect(extras["creatine"] == 5)
    }

    @Test func oldEntriesDecodeWithoutAminoAcidsAndNewOnesRoundTrip() throws {
        let legacy = Data("""
        {"id":"\(UUID().uuidString)","name":"Toast","calories":80,"protein":3,"carbs":15,"fat":1,
         "timestamp":0,"source":"manual","mealType":"breakfast"}
        """.utf8)
        let decodedLegacy = try JSONDecoder().decode(FoodEntry.self, from: legacy)
        #expect(AminoAcidEstimates.dailyTotals([decodedLegacy]).isEmpty)

        let entry = FoodEntry(
            name: "Eggs",
            calories: 140,
            protein: 12,
            carbs: 1,
            fat: 10,
            source: .snapFood,
            supplementalNutrients: [AminoAcid.leucine.storageKey: 1.1, "creatine": 0.1]
        )
        let decoded = try JSONDecoder().decode(FoodEntry.self, from: JSONEncoder().encode(entry))
        #expect(decoded.supplementalNutrients[AminoAcid.leucine.storageKey] == 1.1)
        #expect(decoded.supplementalNutrients["creatine"] == 0.1)
    }

    @Test func dailyTotalsSumOnlyEntriesThatCarryEstimates() {
        let entries = [
            FoodEntry(name: "Eggs", calories: 140, protein: 12, carbs: 1, fat: 10, source: .snapFood,
                      supplementalNutrients: [AminoAcid.leucine.storageKey: 1.1, AminoAcid.lysine.storageKey: 0.9]),
            FoodEntry(name: "Chicken", calories: 165, protein: 31, carbs: 0, fat: 3.6, source: .textInput,
                      supplementalNutrients: [AminoAcid.leucine.storageKey: 2.4, "creatine": 0.4]),
            FoodEntry(name: "Barcode bar", calories: 200, protein: 20, carbs: 20, fat: 7, source: .barcode),
        ]
        let totals = AminoAcidEstimates.dailyTotals(entries)

        #expect(abs((totals[.leucine] ?? 0) - 3.5) < 0.0001)
        #expect(totals[.lysine] == 0.9)
        #expect(totals[.tryptophan] == nil)
        #expect(totals.count == 2)
    }

    @Test func referenceIntakeUsesWHOAdultRequirementTimesBodyWeight() throws {
        let expected: [AminoAcid: Double] = [
            .histidine: 0.7, .isoleucine: 1.4, .leucine: 2.73, .lysine: 2.1, .methionine: 1.05,
            .phenylalanine: 1.75, .threonine: 1.05, .tryptophan: 0.28, .valine: 1.82,
        ]
        for aminoAcid in AminoAcid.allCases {
            let grams = try #require(aminoAcid.referenceGrams(bodyWeightKg: 70))
            #expect(abs(grams - (expected[aminoAcid] ?? -1)) < 0.0001, "\(aminoAcid)")
        }
        #expect(AminoAcid.leucine.referenceGrams(bodyWeightKg: 0) == nil)
        #expect(AminoAcid.leucine.referenceGrams(bodyWeightKg: -60) == nil)
        #expect(AminoAcid.leucine.referenceGrams(bodyWeightKg: .nan) == nil)
    }

    @Test func promptsOnlyAskForAminoAcidsWhenTheSettingIsOn() {
        let off = GeminiService.foodNutrientInstruction(includingAminoAcids: false)
        let on = GeminiService.foodNutrientInstruction(includingAminoAcids: true)

        #expect(on.hasPrefix(off))
        for aminoAcid in AminoAcid.allCases {
            #expect(!off.contains("\"\(aminoAcid.jsonKey)\""))
            #expect(on.contains("\"\(aminoAcid.jsonKey)\":0.0"))
            #expect(AminoAcidEstimates.labelPromptInstruction.contains("\"\(aminoAcid.jsonKey)_per_100g\":0.0"))
        }
        #expect(AminoAcidSettings.enabledKey == "aminoAcidEstimatesEnabled")
    }

    @Test func storageKeysNeverCollideWithSportsCompounds() {
        let sportsKeys = Set(SupplementalNutrient.allCases.map(\.rawValue))
        #expect(sportsKeys.isDisjoint(with: AminoAcid.allCases.map(\.storageKey)))
    }
}
