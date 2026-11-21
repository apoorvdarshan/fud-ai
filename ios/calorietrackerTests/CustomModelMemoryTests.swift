import Foundation
import Testing
@testable import calorietracker

@MainActor
struct CustomModelMemoryTests {
    @Test func switchingAwayPreservesThePreviouslySelectedCustomModel() throws {
        let suite = "CustomModelMemoryTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(AIProvider.customOpenAI.rawValue, forKey: "selectedAIProvider")
        defaults.set("vendor/example", forKey: "selectedAIModel")

        #expect(AIProviderSettings.switchPrimaryProvider(to: .gemini, defaults: defaults) == AIProvider.gemini.defaultModel)
        #expect(AIProviderSettings.switchPrimaryProvider(to: .customOpenAI, defaults: defaults) == "vendor/example")
        #expect(defaults.string(forKey: "selectedAIModel") == "vendor/example")
    }
}
