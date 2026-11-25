import Foundation

/// Only explicitly allowed tools can run. The model can never approve a write.
struct LocalVoiceTools {
    let specs: [VoiceAssistantToolSpec]
    let declarations: String
    private let allowed: [String: LiveToolDeclaration]

    init(workouts: Bool, connected: Bool) {
        let allowedNames = Self.local.union(connected ? Self.networked : [])
        specs = VoiceAssistantToolCatalog.bundled.filter { allowedNames.contains($0.name) }.map { spec in
            VoiceAssistantToolSpec(name: spec.name, description: spec.description, effect: spec.effect,
                                   confirm: spec.effect == .write ? .always : spec.confirm,
                                   requiresWorkoutDiary: spec.requiresWorkoutDiary, parametersJSON: spec.parametersJSON)
        }
        let tools = VoiceAssistantToolCatalog.declarations(specs: specs, workoutDiaryEnabled: workouts)
            .filter { allowedNames.contains($0.name) }
        allowed = Dictionary(uniqueKeysWithValues: tools.map { ($0.name, $0) })
        declarations = tools.map { tool in
            var schema = tool.parameters ?? [:]
            var fields = schema["properties"] as? [String: Any] ?? [:]
            fields.removeValue(forKey: "user_confirmed")
            if tool.name == "log_food" { fields.removeValue(forKey: "item") }
            schema["properties"] = fields
            let description = tool.name == "log_food"
                ? "Prepare a food save from a saved_food_id or a draft_id returned by a tool. Never invent an ID."
                : String(tool.description.prefix(190))
            return LiveJSON.string(["name": tool.name, "description": description, "parameters": schema])
        }.joined(separator: "\n")
    }

    struct Call {
        let request: LiveToolCall
        let wire: String

        func continuation(result: [String: Any]) -> String {
            var data = LiveJSON.string(result)
            if data.utf8.count > 1250 {
                var compact = result.mapValues { value -> Any in
                    if let array = value as? [Any] { return Array(array.prefix(3)) }
                    return value
                }
                compact["truncated"] = true
                data = LiveJSON.string(compact)
                if data.utf8.count > 1250 {
                    data = #"{"truncated":true,"message":"Too much data. Ask for a narrower date range or a more specific food."}"#
                }
            }
            data = data.replacingOccurrences(of: "<", with: "\\u003c").replacingOccurrences(of: ">", with: "\\u003e")
            return "<|im_start|>assistant\n\(wire)<|im_end|>\n<|im_start|>tool\n<tool_response>\n\(data)\n</tool_response><|im_end|>\n"
        }
    }

    func parse(_ text: String) -> Call? {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.utf8.count <= 1600,
              let match = Self.call.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let nameRange = Range(match.range(at: 1), in: text),
              let bodyRange = Range(match.range(at: 2), in: text) else { return nil }
        let name = String(text[nameRange])
        guard let tool = allowed[name] else { return nil }
        let properties = tool.parameters?["properties"] as? [String: Any] ?? [:]
        let body = String(text[bodyRange])
        var cursor = 0
        var args: [String: Any] = [:]
        for part in Self.parameter.matches(in: body, range: NSRange(body.startIndex..., in: body)) {
            guard cursor == part.range.location,
                  let keyRange = Range(part.range(at: 1), in: body),
                  let valueRange = Range(part.range(at: 2), in: body) else { return nil }
            cursor = NSMaxRange(part.range)
            let key = String(body[keyRange])
            let value = body[valueRange].trimmingCharacters(in: .whitespacesAndNewlines)
            guard key != "user_confirmed", !(name == "log_food" && key == "item"), properties[key] != nil, args[key] == nil,
                  value.utf8.count <= 500, !value.contains("<") else { return nil }
            args[key] = (try? JSONSerialization.jsonObject(with: Data(value.utf8), options: [.fragmentsAllowed])) ?? value
        }
        guard cursor == body.utf16.count else { return nil }
        return Call(request: .init(id: "local", name: name, argumentsJSON: LiveJSON.string(args)), wire: text)
    }

    private static let call = try! NSRegularExpression(
        pattern: #"\A<tool_call>\s*<function=([a-z_]+)>\s*(.*?)</function>\s*</tool_call>\z"#,
        options: .dotMatchesLineSeparators
    )
    private static let parameter = try! NSRegularExpression(
        pattern: #"<parameter=([a-z_]+)>\s*(.*?)\s*</parameter>\s*"#, options: .dotMatchesLineSeparators
    )
    private static let local: Set<String> = [
        "get_food_entries", "get_calorie_totals", "get_weight_history", "get_fasting_history", "get_workout_history",
        "search_foods", "log_food", "log_water", "log_weight", "log_body_fat", "start_fast", "end_fast",
        "log_cardio", "set_diary_date", "open_food_entry"
    ]
    private static let networked: Set<String> = [
        "describe_food", "open_camera", "capture_photo", "close_camera", "pick_photos",
        "scan_barcode", "describe_workout", "log_workout"
    ]
}
