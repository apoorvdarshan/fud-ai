import Foundation
import Testing
@testable import calorietracker

struct ExerciseImageReportTests {
    @Test func reportCarriesCatalogMetadataAndKeepsTheUsersNoteLiteral() throws {
        let note = "Frame 2: हाथ + leg & angle\n@person"
        let url = try #require(ExerciseImageReport.issueURL(
            exercise: "Lunge", id: "Lunge", visual: "Lunge_female_v2_0.png", version: "7.0", note: note
        ))
        let parts = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(parts.host == "github.com")
        #expect(parts.path == "/apoorvdarshan/fud-ai/issues/new")
        let query = Dictionary(uniqueKeysWithValues: (parts.queryItems ?? []).compactMap { item in item.value.map { (item.name, $0) } })
        #expect(query["template"] == "exercise_visual.md")
        #expect(query["body"]?.contains("    Frame 2: हाथ + leg & angle\n    @person") == true)
        #expect(query["body"]?.contains("Lunge_female_v2_0.png") == true)
        #expect(url.absoluteString.contains("%2B"))
    }
}
