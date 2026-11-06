import Foundation
import Testing
@testable import calorietracker

/// The AI reply-language instruction follows the shown app language and leaves English alone.
struct AIReplyLanguageTests {
    @Test func englishAddsNothing() {
        #expect(AIReplyLanguage.instruction(for: "en") == nil)
        #expect(AIReplyLanguage.instruction(for: "en-US") == nil)
        #expect(AIReplyLanguage.prompt("Analyze this image.", instruction: AIReplyLanguage.instruction(for: "en")) == "Analyze this image.")
        #expect(AIReplyLanguage.plainLanguageName(for: "en") == "English")
    }

    @Test func czechNamesTheLanguageAndKeepsIdentifiersEnglish() {
        let instruction = AIReplyLanguage.instruction(for: "cs")
        #expect(instruction?.contains("in Czech") == true)
        #expect(instruction?.contains("exercise_id") == true)
        #expect(instruction?.contains("intensity") == true)
        #expect(instruction?.contains("in English") == true)
        #expect(AIReplyLanguage.prompt("Analyze this image.", instruction: instruction) == (instruction ?? "") + "\n\nAnalyze this image.")
        #expect(AIReplyLanguage.plainLanguageName(for: "cs") == "Czech")
    }

    @Test func regionalTagsUseTheFullLanguageName() {
        #expect(AIReplyLanguage.instruction(for: "pt-BR")?.contains("Portuguese (Brazil)") == true)
        #expect(AIReplyLanguage.languageName(for: "zh-Hans")?.hasPrefix("Chinese") == true)
    }
}
