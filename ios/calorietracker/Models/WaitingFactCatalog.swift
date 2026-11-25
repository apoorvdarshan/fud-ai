import Foundation

nonisolated struct WaitingFactCatalog: Decodable, Sendable {
    struct Food: Decodable, Sendable {
        let id: Int
        let preparation: String
        let names: [String: String]
        let values: [String: Double]
    }

    let revision: String
    let foods: [Food]
    static let empty = Self(revision: "fallback", foods: [])
    static let loaded = Task.detached(priority: .utility) {
        guard let url = Bundle.main.url(forResource: "analysis_nutrition_facts", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let catalog = try? JSONDecoder().decode(Self.self, from: data) else { return Self.empty }
        return catalog
    }

    enum Nutrient: String, CaseIterable, Sendable {
        case protein, fat, carbs, water, fiber, calcium, iron, magnesium, potassium, sodium, zinc
        case vitaminA = "vitamin_a"
        case vitaminE = "vitamin_e"
        case vitaminD = "vitamin_d"
        case vitaminC = "vitamin_c"
        case folate
        case vitaminB12 = "vitamin_b12"
        case vitaminK = "vitamin_k"
        case sugar

        var title: String.LocalizationValue {
            switch self {
            case .protein: "Protein"
            case .fat: "Fat"
            case .carbs: "Carbs"
            case .water: "Water"
            case .fiber: "Fiber"
            case .calcium: "Calcium"
            case .iron: "Iron"
            case .magnesium: "Magnesium"
            case .potassium: "Potassium"
            case .sodium: "Sodium"
            case .zinc: "Zinc"
            case .vitaminA: "Vitamin A"
            case .vitaminE: "Vitamin E"
            case .vitaminD: "Vitamin D"
            case .vitaminC: "Vitamin C"
            case .folate: "Folate"
            case .vitaminB12: "Vitamin B12"
            case .vitaminK: "Vitamin K"
            case .sugar: "Sugar"
            }
        }

        var unit: UnitMass {
            switch self {
            case .protein, .fat, .carbs, .water, .fiber, .sugar: .grams
            case .vitaminA, .vitaminD, .folate, .vitaminB12, .vitaminK: .micrograms
            default: .milligrams
            }
        }
    }

    struct Measurement: Sendable {
        let food: String
        let preparation: String
        let nutrient: Nutrient
        let amount: Double
    }

    func measurements(language: String) -> [Measurement] {
        foods.flatMap { food in
            guard let name = food.names[language] else { return [] as [Measurement] }
            return Nutrient.allCases.compactMap { nutrient in
                guard let amount = food.values[nutrient.rawValue], amount.isFinite, amount > 0 else { return nil }
                return Measurement(food: name, preparation: food.preparation, nutrient: nutrient, amount: amount)
            }
        }
    }

    static func language(for locale: Locale) -> String {
        switch locale.language.languageCode?.identifier {
        case "pt": "pt-BR"
        case "zh": "zh-Hans"
        case "ar", "az", "cs", "de", "es", "fr", "hi", "it", "ja", "ko", "nl", "pl", "ro", "ru", "tr", "uk":
            locale.language.languageCode?.identifier ?? "en"
        default: "en"
        }
    }
}

/// Persist the shuffle seed and position, not a history of foods or requests.
/// A seed deterministically reconstructs a permutation across app launches.
nonisolated enum WaitingFactDeck {
    static let editorialCount = 5

    static func order(measurementCount: Int, seed: UInt64) -> [Int] {
        var random = SeededGenerator(state: seed)
        func shuffled(_ range: Range<Int>) -> [Int] {
            var cards = Array(range)
            guard cards.count > 1 else { return cards }
            for index in stride(from: cards.count - 1, through: 1, by: -1) {
                cards.swapAt(index, Int(random.next() % UInt64(index + 1)))
            }
            return cards
        }
        let editorial = shuffled(0..<editorialCount)
        var measured = shuffled(editorialCount..<(editorialCount + measurementCount))
        for (index, card) in editorial.enumerated() {
            measured.insert(card, at: min(index * 9, measured.count))
        }
        return measured
    }

    private struct SeededGenerator {
        var state: UInt64
        mutating func next() -> UInt64 {
            state &+= 0x9e3779b97f4a7c15
            var value = state
            value = (value ^ (value >> 30)) &* 0xbf58476d1ce4e5b9
            value = (value ^ (value >> 27)) &* 0x94d049bb133111eb
            return value ^ (value >> 31)
        }
    }
}

@MainActor
final class WaitingFactFeed {
    static let shared = WaitingFactFeed()

    enum Card {
        case editorial(Int)
        case measurement(WaitingFactCatalog.Measurement)
    }

    private struct Deck {
        let revision: String
        let seed: UInt64
        let order: [Int]
        var offset: Int
    }

    private let defaults: UserDefaults
    private var preparedCatalog: WaitingFactCatalog?
    private var decks: [String: Deck] = [:]
    private var measurementsByLanguage: [String: (revision: String, entries: [WaitingFactCatalog.Measurement])] = [:]

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    /// Start loading before the first analysis. Never make a loading screen wait for it.
    func prepare() async {
        guard preparedCatalog == nil else { return }
        preparedCatalog = await WaitingFactCatalog.loaded.value
    }

    func nextReady(language: String) -> Card {
        guard let preparedCatalog, !preparedCatalog.foods.isEmpty else {
            // Cold or missing asset: don't reset the saved position of the full deck.
            return .editorial(Int.random(in: 0..<WaitingFactDeck.editorialCount))
        }
        return next(catalog: preparedCatalog, language: language)
    }

    func next(catalog: WaitingFactCatalog, language: String) -> Card {
        if measurementsByLanguage[language]?.revision != catalog.revision {
            measurementsByLanguage[language] = (catalog.revision, catalog.measurements(language: language))
        }
        let measurements = measurementsByLanguage[language]?.entries ?? []
        let key = "analysis.waitingFactDeck.v1.\(language)"
        let count = WaitingFactDeck.editorialCount + measurements.count
        var deck = decks[language]
        if deck?.revision != catalog.revision || deck?.order.count != count {
            let saved = defaults.stringArray(forKey: key)
            if let saved, saved.count == 3, saved[0] == catalog.revision,
               let seed = UInt64(saved[1]), let offset = Int(saved[2]), (0..<count).contains(offset) {
                deck = Deck(revision: catalog.revision, seed: seed,
                            order: WaitingFactDeck.order(measurementCount: measurements.count, seed: seed), offset: offset)
            } else {
                deck = nil
            }
        }
        if var deck, deck.offset < count {
            let card = deck.order[deck.offset]
            deck.offset += 1
            store(deck, language: language, key: key)
            return Self.card(card, measurements: measurements)
        }
        let seed = UInt64.random(in: UInt64.min...UInt64.max)
        let order = WaitingFactDeck.order(measurementCount: measurements.count, seed: seed)
        let fresh = Deck(revision: catalog.revision, seed: seed, order: order, offset: 1)
        store(fresh, language: language, key: key)
        return Self.card(order[0], measurements: measurements)
    }

    private func store(_ deck: Deck, language: String, key: String) {
        decks[language] = deck
        defaults.set([deck.revision, String(deck.seed), String(deck.offset)], forKey: key)
    }

    private static func card(_ index: Int, measurements: [WaitingFactCatalog.Measurement]) -> Card {
        if index < WaitingFactDeck.editorialCount { return .editorial(index) }
        return .measurement(measurements[index - WaitingFactDeck.editorialCount])
    }
}
