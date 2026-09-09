import Testing
@testable import calorietracker

struct OpenRouterReasoningEffortTests {
    @Test func autoPreservesAnalysisAndCoachDefaults() {
        let analysis = OpenRouterReasoningEffort.auto.requestOptions(compactRetry: false, exclude: true)
        #expect(analysis?["exclude"] as? Bool == true)
        #expect(analysis?["effort"] == nil)
        #expect(OpenRouterReasoningEffort.auto.requestOptions(compactRetry: false, exclude: false) == nil)
    }

    @Test func explicitLevelsApplyToBothRoutes() {
        for effort in OpenRouterReasoningEffort.allCases where effort != .auto {
            for exclude in [false, true] {
                let options = effort.requestOptions(compactRetry: false, exclude: exclude)
                #expect(options?["effort"] as? String == effort.rawValue)
                #expect((options?["exclude"] as? Bool) == (exclude ? true : nil))
            }
        }
    }

    @Test func retriesPreserveLowEffortRecovery() {
        for effort in OpenRouterReasoningEffort.allCases {
            for exclude in [false, true] {
                let options = effort.requestOptions(compactRetry: true, exclude: exclude)
                #expect(options?["effort"] as? String == "low")
                #expect(options?["exclude"] as? Bool == true)
            }
        }
    }
}
