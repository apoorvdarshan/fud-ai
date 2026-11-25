import Foundation

/// Stateless Responses tool turns; no provider-side stored conversation is required.
enum OpenAIResponsesWire {
    struct Call {
        let id: String
        let name: String
        let arguments: String
    }

    struct Turn {
        let items: [[String: Any]]
        let calls: [Call]
        let text: String?
    }

    enum DecodingError: Error { case invalid, incomplete }

    static func request(
        model: String, instructions: String, input: [[String: Any]],
        tools: [[String: Any]], maxTokens: Int
    ) -> [String: Any] {
        [
            "model": model, "instructions": instructions, "input": input,
            "tools": tools, "tool_choice": "auto", "max_output_tokens": max(16, maxTokens),
            "reasoning": ["effort": "low"], "store": false, "include": ["reasoning.encrypted_content"],
        ]
    }

    static func toolOutput(callID: String, result: String) -> [String: Any] {
        ["type": "function_call_output", "call_id": callID, "output": result]
    }

    static func decode(_ data: Data) throws -> Turn {
        guard let body = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DecodingError.invalid
        }
        if body["status"] as? String == "incomplete" { throw DecodingError.incomplete }
        guard body["status"] as? String == "completed", let items = body["output"] as? [[String: Any]] else {
            throw DecodingError.invalid
        }
        var calls: [Call] = []
        var text: [String] = []
        for item in items {
            switch item["type"] as? String {
            case "function_call":
                guard let id = item["call_id"] as? String, !id.isEmpty, !calls.contains(where: { $0.id == id }),
                      let name = item["name"] as? String, !name.isEmpty,
                      let arguments = item["arguments"] as? String else { throw DecodingError.invalid }
                calls.append(Call(id: id, name: name, arguments: arguments))
            case "message":
                text += ((item["content"] as? [[String: Any]]) ?? [])
                    .filter { $0["type"] as? String == "output_text" }
                    .compactMap { ($0["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
                    .filter { !$0.isEmpty }
            default: break
            }
        }
        return Turn(items: items, calls: calls, text: text.isEmpty ? nil : text.joined(separator: "\n"))
    }
}
