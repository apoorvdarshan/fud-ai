import Testing
@testable import calorietracker

@MainActor
struct LocalModelDownloadBudgetTests {
    @Test func preventsTwoModelsFromOvercommittingFreeSpaceAndReleasesCapacity() throws {
        let budget = LocalModelDownloadBudget()
        let first = try #require(budget.reserve(requiredBytes: 3_600, availableBytes: 5_000))
        #expect(budget.unreservedBytes(availableBytes: 5_000) == 1_400)
        #expect(budget.reserve(requiredBytes: 4_600, availableBytes: 5_000) == nil)
        #expect(budget.reserve(requiredBytes: 1_400, availableBytes: 5_000) != nil)
        #expect(budget.unreservedBytes(availableBytes: 1_000) == 0)
        budget.release(first)
        #expect(budget.reserve(requiredBytes: 3_600, availableBytes: 5_000) != nil)
    }
}
