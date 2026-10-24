import Foundation
import Testing
@testable import calorietracker

struct MealShareTests {
    private var entries: [FoodEntry] {
        [FoodEntry(name: "🥚 Eggs", calories: 124, protein: 10, carbs: 1, fat: 8, source: .manual)]
    }

    @Test func legacyLinksStillRoundTrip() throws {
        let link = try #require(MealShare.link(for: entries))
        let imported = try #require(MealShare.meals(from: link))
        #expect(imported.first?.name == "🥚 Eggs")
        #expect(imported.first?.calories == 124)
        let query = try #require(URLComponents(url: link, resolvingAgainstBaseURL: false)?.percentEncodedQuery)
        let deepLink = try #require(URL(string: "fudai://add-meal?\(query)"))
        #expect(MealShare.meals(from: deepLink)?.first?.protein == 10)
    }

    /// The payload a link carries, for comparing what two entries would share.
    private func sharedPayload(_ entries: [FoodEntry]) throws -> NSDictionary {
        let link = try #require(MealShare.link(for: entries))
        let items = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let value = items.first(where: { $0.name == "d" })?.value
        let encoded = try #require(value)
        var base64 = encoded.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        let data = try #require(Data(base64Encoded: base64))
        return try #require(JSONSerialization.jsonObject(with: data) as? NSDictionary)
    }

    /// Every field the app shows survives a share; only photos, ids and time stay behind.
    @Test func everyNutritionFieldRoundTrips() throws {
        let shared = FoodEntry(
            name: "Granola bowl", calories: 512, protein: 18.5, carbs: 61.25, fat: 21.75,
            emoji: "🥣", source: .barcode, mealType: .breakfast,
            sugar: 14.5, addedSugar: 6, fiber: 8.25, saturatedFat: 4.5, monounsaturatedFat: 9.5,
            polyunsaturatedFat: 5.25, cholesterol: 12, caffeine: 40,
            supplementalNutrients: ["creatine": 3, "leucine": 1.8],
            sodium: 220, potassium: 610, transFat: 0.1, calcium: 180, iron: 3.4, magnesium: 95,
            zinc: 2.1, vitaminA: 120, vitaminC: 9.5, vitaminD: 2.5, vitaminB12: 0.9, vitaminE: 3.2,
            vitaminK: 14, folate: 60, omega3: 1.2,
            servingSizeGrams: 135,
            servingUnitOptions: [ServingUnitOption(unit: "cup", gramsPerUnit: 90, quantity: 1.5), ServingUnitOption(unit: "g", gramsPerUnit: 1, quantity: nil)],
            selectedServingUnit: "cup", selectedServingQuantity: 1.5,
            customNote: "with oat milk", progressiveMeal: true,
            ingredients: [
                MealIngredient(name: "Granola", grams: 60, calories: 280, protein: 7, carbs: 38, fat: 11, emoji: "🌾"),
                MealIngredient(name: "Greek yogurt", grams: 75, calories: 232, protein: 11.5, carbs: 23.25, fat: 10.75),
            ],
            productMetadata: FoodProductMetadata(
                barcode: "5000159407236", packageQuantity: "500 g", ingredientsText: "Oats, honey, almonds",
                allergens: ["en:nuts"], traces: ["en:milk"], nutriScore: "b", novaGroup: 3, ecoScore: "c",
                labels: ["en:vegetarian"], categories: ["en:breakfast-cereals"],
                imageURL: URL(string: "https://images.openfoodfacts.org/p.jpg"), dataSource: FoodDataCentralService.dataSourceID
            )
        )
        let link = try #require(MealShare.link(for: [shared]))
        let imported = try #require(MealShare.meals(from: link)?.first)
        // Decoding loses nothing that encoding wrote.
        #expect(try sharedPayload([imported]) == sharedPayload([shared]))
        #expect(imported.source == .manual)
        #expect(imported.mealType == .breakfast)
        #expect(imported.servingSizeGrams == 135)
        #expect(imported.servingUnitOptions == shared.servingUnitOptions)
        #expect(imported.selectedServingUnit == "cup")
        #expect(imported.selectedServingQuantity == 1.5)
        #expect(imported.supplementalNutrients == shared.supplementalNutrients)
        #expect(imported.ingredients.map(\.emoji) == ["🌾", nil])
        #expect(imported.productMetadata == shared.productMetadata)
    }

    /// The exact keys Android writes, so a link made on either platform imports on the other.
    @Test func readsTheSharedPayloadKeys() throws {
        let payload = #"{"v":1,"meals":[{"name":"Toast","calories":180,"protein":6,"carbs":30,"fat":3,"mealType":"lunch","servingSizeGrams":60,"servingUnitOptions":[{"unit":"slice","gramsPerUnit":30,"quantity":2}],"selectedServingUnit":"slice","selectedServingQuantity":2,"ingredients":[{"name":"Bread","grams":60,"calories":180,"protein":6,"carbs":30,"fat":3,"emoji":"🍞"}],"productMetadata":{"barcode":"123","allergens":["en:gluten"],"novaGroup":4,"imageUrl":"https://x.test/i.jpg"}}]}"#
        let d = Data(payload.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let url = try #require(URL(string: "fudai://add-meal?d=\(d)"))
        let toast = try #require(MealShare.meals(from: url)?.first)
        #expect(toast.mealType == .lunch)
        #expect(toast.servingSizeGrams == 60)
        #expect(toast.servingUnitOptions == [ServingUnitOption(unit: "slice", gramsPerUnit: 30, quantity: 2)])
        #expect(toast.selectedServingUnit == "slice")
        #expect(toast.selectedServingQuantity == 2)
        #expect(toast.ingredients.first?.emoji == "🍞")
        #expect(toast.productMetadata == FoodProductMetadata(
            barcode: "123", packageQuantity: nil, ingredientsText: nil, allergens: ["en:gluten"], traces: [],
            nutriScore: nil, novaGroup: 4, ecoScore: nil, labels: [], categories: [], imageURL: URL(string: "https://x.test/i.jpg")
        ))
    }

    @Test func directLinkNeverAsksForAShortLink() async throws {
        var requests = 0
        let result = await MealShare.preferredLink(for: entries, style: .direct) { request in
            requests += 1
            return (Data(), HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!)
        }
        #expect(requests == 0)
        #expect(result == MealShare.link(for: entries))
        #expect(result?.path == MealShare.webPath)
    }

    @Test func prefersShortLinkAndPostsOnlyMealPayload() async throws {
        let short = "https://www.fud-ai.app/m/abcdefghijklmnopqrstuv"
        let result = await MealShare.preferredLink(for: entries) { request in
            #expect(request.url?.path == "/api/meal-shares")
            #expect(request.httpMethod == "POST")
            #expect(request.timeoutInterval == 5)
            #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
            #expect(request.value(forHTTPHeaderField: "User-Agent") == "FudAI/1.0 (iOS; MealShare)")
            let body = try #require(request.httpBody)
            let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            #expect(payload["v"] as? Int == 1)
            #expect((payload["meals"] as? [[String: Any]])?.first?["name"] as? String == "🥚 Eggs")
            return (Data("{\"url\":\"\(short)\"}".utf8), HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!)
        }
        #expect(result?.absoluteString == short)
        let text = MealShare.shareText(for: entries, using: result)
        #expect(text.contains(short))
        #expect(!text.contains("?d="))
    }

    @Test func rateLimitFallsBackWithoutRetry() async throws {
        var attempts = 0
        let result = await MealShare.preferredLink(for: entries) { request in
            attempts += 1
            return (Data("{}".utf8), HTTPURLResponse(url: request.url!, statusCode: 429, httpVersion: nil, headerFields: nil)!)
        }
        #expect(attempts == 1)
        #expect(result?.path == MealShare.webPath)
    }

    @Test func failuresKeepTheLongLink() async {
        let offline = await MealShare.preferredLink(for: entries) { _ in throw URLError(.notConnectedToInternet) }
        #expect(offline?.path == MealShare.webPath)
        #expect(offline.flatMap { MealShare.meals(from: $0) }?.first?.name == "🥚 Eggs")
        for (status, body) in [(429, "{}"), (503, "{}"), (201, "invalid JSON"), (201, "{\"url\":\"https://evil.example/m/abcdefghijklmnopqrstuv\"}")] {
            let result = await MealShare.preferredLink(for: entries) { request in
                (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
            }
            #expect(result?.path == MealShare.webPath)
            #expect(result.flatMap { MealShare.meals(from: $0) }?.first?.calories == 124)
        }
    }
}
