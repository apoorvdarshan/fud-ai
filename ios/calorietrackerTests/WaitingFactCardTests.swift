import SwiftUI
import Testing
@testable import calorietracker

@MainActor
struct WaitingFactCardTests {
    @Test func factCardIsNotBlankBeforeTheCatalogLoadsOrAppearanceHooksRun() throws {
        let view = WaitingFactCard().frame(width: 290)
        let size = try renderedSize(view)
        #expect(size.width == 290)
        #expect(size.height >= 60)
    }

    @Test func aColdFallbackDoesNotAdvanceOrReplaceTheFullDeck() async throws {
        let suite = "waiting-facts-cold-test-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let feed = WaitingFactFeed(defaults: defaults)
        let cold = feed.nextReady(language: "en")
        guard case .editorial = cold else {
            Issue.record("Expected a readable editorial fact before catalog preparation")
            return
        }
        let key = "analysis.waitingFactDeck.v1.en"
        #expect(defaults.object(forKey: key) == nil)
        await feed.prepare()
        _ = feed.nextReady(language: "en")
        let saved = try #require(defaults.stringArray(forKey: key))
        let catalog = await WaitingFactCatalog.loaded.value
        #expect(saved[0] == catalog.revision)
        #expect(saved[2] == "1")
        guard case .measurement = feed.nextReady(language: "en") else {
            Issue.record("Expected the warmed feed to serve USDA facts as well")
            return
        }
    }

    @Test func shipsTheWholeCatalogAndEveryLanguageHasTheSameFacts() async {
        let catalog = await WaitingFactCatalog.loaded.value
        #expect(catalog.foods.count == 140)
        for language in ["en", "ar", "az", "cs", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "pt-BR", "ro", "ru", "tr", "uk", "zh-Hans"] {
            #expect(catalog.measurements(language: language).count == 1965)
        }
        let banana = catalog.foods.first { $0.id == 173944 }
        #expect(banana?.values["potassium"] == 358)
        #expect(banana?.names["hi"] == "केला")
    }

    @Test func shuffleUsesEveryFactOnceAndResumesFromTheSavedPosition() throws {
        let order = WaitingFactDeck.order(measurementCount: 1965, seed: 1234)
        #expect(order.count == 1970)
        #expect(Set(order) == Set(0..<1970))
        #expect(order == WaitingFactDeck.order(measurementCount: 1965, seed: 1234))

        let suite = "waiting-facts-test-\(UUID())"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = WaitingFactFeed(defaults: defaults).next(catalog: .empty, language: "en")
        let reopened = WaitingFactFeed(defaults: defaults)
        let all = [first] + (0..<4).map { _ in reopened.next(catalog: .empty, language: "en") }
        let indices = all.compactMap { card -> Int? in
            guard case .editorial(let index) = card else { return nil }
            return index
        }
        #expect(Set(indices) == Set(0..<5))
    }

    @Test func factCardWrapsAtAccessibilitySizesInNarrowRightToLeftLayout() throws {
        let card = WaitingFactCard(card: .measurement(.init(
            food: "الفاصوليا الحمراء", preparation: "boiled_no_salt", nutrient: .fiber, amount: 6.4
        )))
            .environment(\.locale, Locale(identifier: "ar"))
            .environment(\.layoutDirection, .rightToLeft)
            .frame(width: 260)

        let regular = try renderedSize(card.dynamicTypeSize(.large))
        let accessibility = try renderedSize(card.dynamicTypeSize(.accessibility3))
        #expect(regular.width == 260)
        #expect(accessibility.width == regular.width)
        #expect(accessibility.height > regular.height)
    }

    private func renderedSize(_ view: some View) throws -> CGSize {
        let renderer = ImageRenderer(content: view)
        return try #require(renderer.uiImage).size
    }
}
