import Testing
@testable import calorietracker

@MainActor
struct GroqModelTests {
    @Test func retiredQwenModelResolvesToItsReplacementOnBothRoutes() {
        let retired = "qwen/qwen3.6-27b"
        let replacement = "qwen/qwen3.8-27b"
        #expect(AIProvider.groq.supportedModelOrDefault(retired) == replacement)
        #expect(AIProvider.groq.supportedTextModelOrDefault(retired) == replacement)
        #expect(!AIProvider.groq.textModels.contains(retired))
    }
}
