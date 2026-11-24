import Foundation
import Testing
@testable import calorietracker

@MainActor
struct OpenAIResponsesWireTests {
    @Test func responsesPreservesReasoningItemsAndLinksToolResultByCallID() throws {
        let turn = try OpenAIResponsesWire.decode(Data("""
            {"status":"completed","output":[
              {"type":"reasoning","encrypted_content":"opaque"},
              {"type":"function_call","id":"fc_output","call_id":"call_fixture","name":"get_app_state","arguments":"{}"}
            ]}
            """.utf8))
        let call = try #require(turn.calls.first)
        #expect(call.name == "get_app_state")
        #expect(turn.items.first?["encrypted_content"] as? String == "opaque")
        let input = turn.items + [OpenAIResponsesWire.toolOutput(callID: call.id, result: #"{"ready":true}"#)]
        let request = OpenAIResponsesWire.request(
            model: "gpt-6.2-sol", instructions: "Be concise.", input: input, tools: [], maxTokens: 1024
        )
        #expect(request["store"] as? Bool == false)
        #expect(request["include"] as? [String] == ["reasoning.encrypted_content"])
        #expect((request["input"] as? [[String: Any]])?.last?["call_id"] as? String == "call_fixture")
        #expect(AIProvider.openai.needsResponsesForTools("gpt-6.2-sol"))
        #expect(AIProvider.openai.needsResponsesForTools("gpt-6.1-terra"))
        #expect(!AIProvider.openai.needsResponsesForTools("gpt-6-luna"))
    }

    @Test func incompleteAndDuplicatedToolCallsCannotExecute() {
        #expect(throws: OpenAIResponsesWire.DecodingError.incomplete) {
            try OpenAIResponsesWire.decode(Data(#"{"status":"incomplete","output":[]}"#.utf8))
        }
        let call = #"{"type":"function_call","call_id":"same","name":"get_app_state","arguments":"{}"}"#
        #expect(throws: OpenAIResponsesWire.DecodingError.invalid) {
            try OpenAIResponsesWire.decode(Data(#"{"status":"completed","output":["#.utf8) + Data("\(call),\(call)]}".utf8))
        }
    }

    @Test func compatibleResponseHidesThinkingChunks() {
        let message = ["content": [
            ["type": "thinking", "text": "internal"],
            ["type": "text", "text": "Two eggs"],
        ]]
        #expect(GeminiService.compatibleVisibleText(message) == "Two eggs")
    }
}
