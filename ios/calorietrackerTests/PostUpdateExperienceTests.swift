import Foundation
import Testing
@testable import calorietracker

struct PostUpdateExperienceTests {
    @Test func hostedOfferRequiresPaidPlansButNotTrialEligibility() {
        #expect(PostUpdatePrompts.shouldOfferHostedUpgrade(mode: .byok, hasEntitlement: false, hasPurchasablePlans: true))
        #expect(!PostUpdatePrompts.shouldOfferHostedUpgrade(mode: .byok, hasEntitlement: false, hasPurchasablePlans: false))
        #expect(!PostUpdatePrompts.shouldOfferHostedUpgrade(mode: .byok, hasEntitlement: true, hasPurchasablePlans: true))
        #expect(!PostUpdatePrompts.shouldOfferHostedUpgrade(mode: .hosted, hasEntitlement: false, hasPurchasablePlans: true))
    }

    @Test func failedRefreshKeepsKnownUpdateButConfirmedUpToDateClearsIt() throws {
        let url = try #require(URL(string: "https://apps.apple.com/app/id6758935726"))
        let available = AppUpdateState.available(current: "9.0", latest: "9.1", url: url)
        let failed = AppUpdateState.failed(current: "9.0")
        let upToDate = AppUpdateState.upToDate(current: "9.0", latest: "9.0")

        #expect(AppUpdateState.idle.resolving(failed) == failed)
        #expect(available.resolving(failed) == available)
        #expect(!available.resolving(upToDate).isUpdateAvailable)
    }
}
