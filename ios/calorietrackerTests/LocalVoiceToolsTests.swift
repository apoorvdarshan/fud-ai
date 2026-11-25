import Testing
@testable import calorietracker

@MainActor
struct LocalVoiceToolsTests {
    @Test func explicitLocalChoicesDoNotResolveCloudProviders() {
        for (choice, connected) in [(VoiceAssistantProviderChoice.offline, false), (.localConnected, true)] {
            #expect(VoiceAssistantRouting.resolve(choice: choice, selectedOwnKeyProvider: .gemini,
                providersWithKeys: [.gemini], hasHostedEntitlement: true) == .local(connectedTools: connected))
            #expect(VoiceAssistantProviderChoice(rawValue: choice.rawValue) == choice)
        }
    }

    @Test func modelCannotApproveWritesOrInvokeUndeclaredTools() {
        let offline = LocalVoiceTools(workouts: false, connected: false)
        let connected = LocalVoiceTools(workouts: true, connected: true)
        #expect(offline.specs.first { $0.name == "log_water" }?.confirm == .always)
        #expect(offline.parse(call("log_water", parameter("amount", "200") + parameter("unit", "ml")))?
            .request.arguments["amount"] as? Int == 200)
        #expect(offline.parse(call("log_water", parameter("user_confirmed", "true"))) == nil)
        #expect(offline.parse(call("log_food", parameter("item", #"{"name":"Egg","calories":80}"#))) == nil)
        #expect(offline.parse(call("scan_barcode")) == nil)
        #expect(connected.parse(call("scan_barcode")) != nil)
        #expect(connected.parse(call("delete_food_entry")) == nil)
        #expect(offline.parse(call("get_workout_history")) == nil)
        #expect(connected.parse(call("get_workout_history")) != nil)
        #expect(offline.parse(call("log_water") + call("log_water")) == nil)
        #expect(connected.declarations.utf8.count < 12_000)
    }

    private func call(_ name: String, _ parameters: String = "") -> String {
        "<tool_call>\n<function=\(name)>\n\(parameters)</function>\n</tool_call>"
    }

    private func parameter(_ name: String, _ value: String) -> String {
        "<parameter=\(name)>\n\(value)\n</parameter>\n"
    }
}
