import SwiftUI
import Testing
@testable import calorietracker

@MainActor
struct WaitingFactCardTests {
    @Test func factCardWrapsAtAccessibilitySizesInNarrowRightToLeftLayout() throws {
        let card = WaitingFactCard()
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
