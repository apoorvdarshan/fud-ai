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

    @Test func prefersShortLinkAndPostsOnlyMealPayload() async throws {
        let short = "https://www.fud-ai.app/m/abcdefghijklmnopqrstuv"
        let result = await MealShare.preferredLink(for: entries) { request in
            #expect(request.url?.path == "/api/meal-shares")
            #expect(request.httpMethod == "POST")
            #expect(request.timeoutInterval == 5)
            #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
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
