import Foundation
import Testing
@testable import calorietracker

/// Setup, encoding and parsing for every live provider, including the hosted relay.
struct VoiceAssistantWireTests {
    private static let tools = [
        LiveToolDeclaration(
            name: "log_water",
            description: "Log water.",
            parametersJSON: #"{"type":"object","properties":{"amount":{"type":"number"},"unit":{"type":"string","enum":["ml","l"]}},"required":["amount","unit"]}"#
        ),
        LiveToolDeclaration(
            name: "get_app_state",
            description: "What the app shows.",
            parametersJSON: #"{"type":"object","properties":{}}"#
        ),
    ]

    private static func config(
        _ provider: LiveProvider, credential: LiveSessionConfig.Credential = .apiKey("secret-key"),
        model: String? = nil, qwenWorkspace: String = "llm-demo"
    ) -> LiveSessionConfig {
        LiveSessionConfig(
            provider: provider,
            model: model ?? provider.defaultModel,
            voice: provider.defaultVoice,
            credential: credential,
            instructions: "Be brief.",
            tools: tools,
            qwenWorkspace: qwenWorkspace
        )
    }

    private static func json(_ text: String) throws -> [String: Any] {
        try #require(LiveJSON.object(text))
    }

    // MARK: Gemini

    @Test func geminiKeepsTheKeyInAHeaderAndDeclaresTools() throws {
        let wire = GeminiLiveWire()
        let request = wire.request(for: Self.config(.gemini))
        #expect(request.value(forHTTPHeaderField: "x-goog-api-key") == "secret-key")
        #expect(request.url?.absoluteString.contains("secret-key") == false)

        let messages = wire.setupMessages(for: Self.config(.gemini), resumptionHandle: "handle-1")
        #expect(messages.count == 1)
        let setup = try #require(try Self.json(messages[0])["setup"] as? [String: Any])
        #expect(setup["model"] as? String == "models/gemini-3.8-live")
        let generation = try #require(setup["generationConfig"] as? [String: Any])
        #expect(Set(generation.keys) == ["responseModalities", "speechConfig"])
        let voice = ((generation["speechConfig"] as? [String: Any])?["voiceConfig"] as? [String: Any])?["prebuiltVoiceConfig"] as? [String: Any]
        #expect(voice?["voiceName"] as? String == "Kore")
        #expect((setup["sessionResumption"] as? [String: Any])?["handle"] as? String == "handle-1")

        let tools = try #require(setup["tools"] as? [[String: Any]])
        let declarations = try #require(tools.first?["functionDeclarations"] as? [[String: Any]])
        #expect(declarations.map { $0["name"] as? String } == ["log_water", "get_app_state"])
        // Only name, description and parameters; no parameters for a tool without arguments.
        #expect(Set(declarations[0].keys) == ["name", "description", "parameters"])
        #expect(Set(declarations[1].keys) == ["name", "description"])
    }

    @Test func geminiParsesToolCallsAndCancellations() throws {
        let wire = GeminiLiveWire()
        let events = wire.parse(#"{"toolCall":{"functionCalls":[{"id":"call-1","name":"log_water","args":{"amount":500,"unit":"ml"}},{"name":"get_app_state"}]}}"#)
        guard case .toolCalls(let calls) = try #require(events.first) else {
            Issue.record("Expected tool calls, got \(events)")
            return
        }
        #expect(calls.map(\.id) == ["call-1", "get_app_state"])
        #expect(calls[0].arguments["amount"] as? Int == 500)
        #expect(calls[0].arguments["unit"] as? String == "ml")
        #expect(calls[1].arguments.isEmpty)

        #expect(wire.parse(#"{"toolCallCancellation":{"ids":["call-1"]}}"#) == [.toolCallCancellation(["call-1"])])
    }

    @Test func geminiAndHostedKeepTheSelectedVoiceAndUseConservativeDetection() throws {
        for provider in [LiveProvider.gemini, .hosted] {
            for (enabled, mode) in [(true, "START_OF_ACTIVITY_INTERRUPTS"), (false, "NO_INTERRUPTION")] {
                let config = LiveSessionConfig(provider: provider, model: provider.defaultModel, voice: "Leda",
                    credential: .apiKey("fake"), instructions: "Be brief.", tools: [], geminiBargeIn: enabled)
                let message = try #require(provider.wire.setupMessages(for: config, resumptionHandle: "resume").first)
                let setup = try #require(try Self.json(message)["setup"] as? [String: Any])
                let input = try #require(setup["realtimeInputConfig"] as? [String: Any])
                let detection = try #require(input["automaticActivityDetection"] as? [String: Any])
                #expect(input["activityHandling"] as? String == mode)
                #expect(detection["startOfSpeechSensitivity"] as? String == "START_SENSITIVITY_LOW")
                #expect(detection["endOfSpeechSensitivity"] as? String == "END_SENSITIVITY_LOW")
                #expect(detection["prefixPaddingMs"] as? Int == 240)
                #expect(detection["silenceDurationMs"] as? Int == 700)
                let generation = try #require(setup["generationConfig"] as? [String: Any])
                let speech = try #require(generation["speechConfig"] as? [String: Any])
                let outer = try #require(speech["voiceConfig"] as? [String: Any])
                #expect((outer["prebuiltVoiceConfig"] as? [String: Any])?["voiceName"] as? String == "Leda")
                let instruction = try #require(setup["systemInstruction"] as? [String: Any])
                #expect((instruction["parts"] as? [[String: String]])?.first?["text"]?.contains("one consistent voice") == true)
            }
        }
    }

    @Test func geminiParsesAudioTranscriptsAndTurns() throws {
        let audio = Data([1, 2, 3, 4])
        let message = """
        {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"\(audio.base64EncodedString())"}}]},"inputTranscription":{"text":"two eggs"},"outputTranscription":{"text":"Got it"},"turnComplete":true}}
        """
        #expect(GeminiLiveWire().parse(message) == [
            .audio(audio, itemID: nil),
            .userText("two eggs", replacing: false),
            .assistantText("Got it"),
            .turnComplete,
        ])
        #expect(GeminiLiveWire().parse(#"{"setupComplete":{}}"#) == [.ready])
        #expect(GeminiLiveWire().parse(#"{"serverContent":{"interrupted":true}}"#) == [.interrupted])
    }

    @Test func geminiEncodesToolResponsesContextTextAndFrames() throws {
        let wire = GeminiLiveWire()
        let responses = wire.toolResponseMessages([
            LiveToolResponse(id: "call-1", name: "log_water", outputJSON: #"{"ok":true,"milliliters":500}"#),
        ])
        let response = try #require(try Self.json(responses[0])["toolResponse"] as? [String: Any])
        let function = try #require((response["functionResponses"] as? [[String: Any]])?.first)
        #expect(function["id"] as? String == "call-1")
        #expect(function["name"] as? String == "log_water")
        #expect((function["response"] as? [String: Any])?["ok"] as? Bool == true)

        let context = try #require(try Self.json(wire.contextTextMessages("[App] The user tapped Undo.")[0])["clientContent"] as? [String: Any])
        #expect(context["turnComplete"] as? Bool == true)
        let parts = (context["turns"] as? [[String: Any]])?.first?["parts"] as? [[String: Any]]
        #expect(parts?.first?["text"] as? String == "[App] The user tapped Undo.")

        let frame = try #require(try Self.json(wire.imageMessages(Data([9]))[0])["realtimeInput"] as? [String: Any])
        #expect((frame["video"] as? [String: Any])?["mimeType"] as? String == "image/jpeg")
        #expect(wire.responseCreateMessage() == nil)
    }

    // MARK: Hosted relay

    @Test func hostedRelayIdentifiesTheUserAndPlatformWithoutAKey() throws {
        let wire = HostedLiveWire()
        let config = Self.config(.hosted, credential: .hostedUser("$RCAnonymousID:abc"))
        let request = wire.request(for: config)
        #expect(request.url?.absoluteString == "wss://fud-ai.app/api/hosted-ai/v1/live")
        #expect(request.value(forHTTPHeaderField: "X-Fud-User-Id") == "$RCAnonymousID:abc")
        #expect(request.value(forHTTPHeaderField: "X-Fud-Platform") == "ios")
        #expect(request.value(forHTTPHeaderField: "x-goog-api-key") == nil)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)

        // The relay accepts only these setup fields.
        let setup = try #require(try Self.json(wire.setupMessages(for: config, resumptionHandle: nil)[0])["setup"] as? [String: Any])
        let allowed: Set<String> = [
            "model", "generationConfig", "systemInstruction", "tools", "inputAudioTranscription",
            "outputAudioTranscription", "contextWindowCompression", "sessionResumption", "realtimeInputConfig",
        ]
        #expect(Set(setup.keys).isSubset(of: allowed))
        #expect((setup["inputAudioTranscription"] as? [String: Any])?.isEmpty == true)
        #expect(wire.inputSampleRate == 16_000)
        #expect(wire.outputSampleRate == 24_000)
    }

    @Test func hostedRelayReadsFudVoiceMessages() {
        let wire = HostedLiveWire()
        #expect(wire.parse(#"{"fudVoice":{"secondsRemaining":1185}}"#) == [.hostedSecondsRemaining(1185)])
        #expect(wire.parse(#"{"fudVoice":{"error":"voice_quota_exceeded"}}"#) == [.hostedError(code: "voice_quota_exceeded", detail: nil)])
        #expect(wire.parse(#"{"fudVoice":{"error":"invalid_setup","detail":"tools"}}"#) == [.hostedError(code: "invalid_setup", detail: "tools")])
        // Gemini's own messages pass through unchanged.
        #expect(wire.parse(#"{"setupComplete":{}}"#) == [.ready])
    }

    // MARK: OpenAI

    @Test func openAIDeclaresToolsSeparatelyAndKeepsTheKeyOutOfTheURL() throws {
        let wire = OpenAIRealtimeWire()
        let request = wire.request(for: Self.config(.openai))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.query == "model=gpt-realtime-2.1-mini")

        let messages = try wire.setupMessages(for: Self.config(.openai), resumptionHandle: nil).map(Self.json)
        #expect(messages.count == 3)
        let core = try #require(messages[0]["session"] as? [String: Any])
        #expect(core["type"] as? String == "realtime")
        #expect(core["instructions"] as? String == "Be brief.")
        let toolsSession = try #require(messages[1]["session"] as? [String: Any])
        #expect(toolsSession["tool_choice"] as? String == "auto")
        let tools = try #require(toolsSession["tools"] as? [[String: Any]])
        #expect(tools.count == 2)
        #expect(tools[0]["type"] as? String == "function")
        #expect(tools[0]["name"] as? String == "log_water")
        #expect((tools[1]["parameters"] as? [String: Any])?["type"] as? String == "object")
        let transcriptionSession = try #require(messages[2]["session"] as? [String: Any])
        let audio = try #require(transcriptionSession["audio"] as? [String: Any])
        let input = try #require(audio["input"] as? [String: Any])
        #expect((input["transcription"] as? [String: Any])?["model"] as? String == "gpt-transcribe")
    }

    @Test func openAIParsesFunctionCallsAndResponseLifecycle() throws {
        let wire = OpenAIRealtimeWire()
        #expect(wire.parse(#"{"type":"response.created","response":{"id":"r1"}}"#) == [.responseStarted])
        #expect(wire.parse(#"{"type":"response.function_call_arguments.done","response_id":"r1","item_id":"item-1","call_id":"call_9","name":"log_water","arguments":"{\"amount\":2,\"unit\":\"cup\"}"}"#).isEmpty)
        let call = wire.parse(#"{"type":"response.done","response":{"id":"r1","status":"completed","output":[{"type":"function_call","call_id":"call_9"}]}}"#)
        guard case .toolCalls(let calls) = try #require(call.first) else {
            Issue.record("Expected a tool call, got \(call)")
            return
        }
        #expect(calls.first?.id == "call_9")
        #expect(calls.first?.arguments["unit"] as? String == "cup")

        #expect(call.last == .responseDone(hasToolCalls: true))
        #expect(wire.parse(#"{"type":"response.created","response":{"id":"r2"}}"#) == [.responseStarted])
        #expect(wire.parse(#"{"type":"response.done","response":{"output":[{"type":"message"}]}}"#) == [.responseDone(hasToolCalls: false)])
        #expect(wire.parse(#"{"type":"input_audio_buffer.speech_started"}"#) == [.userSpeechStarted])
        #expect(wire.parse(#"{"type":"error","error":{"message":"busy","code":"conversation_already_has_active_response"}}"#)
            == [.error("busy", code: "conversation_already_has_active_response")])
    }

    @Test func openAIEncodesFunctionOutputsAndAsksForTheNextResponse() throws {
        let wire = OpenAIRealtimeWire()
        let outputs = wire.toolResponseMessages([LiveToolResponse(id: "call_9", name: "log_water", outputJSON: #"{"ok":true}"#)])
        let item = try #require(try Self.json(outputs[0])["item"] as? [String: Any])
        #expect(item["type"] as? String == "function_call_output")
        #expect(item["call_id"] as? String == "call_9")
        #expect(item["output"] as? String == #"{"ok":true}"#)
        #expect(try Self.json(try #require(wire.responseCreateMessage()))["type"] as? String == "response.create")
        #expect(wire.needsResponseCreate)

        let truncate = try Self.json(try #require(wire.truncateMessage(itemID: "item-1", audioEndMs: -5)))
        #expect(truncate["audio_end_ms"] as? Int == 0)
        #expect(wire.imageMessages(Data([1])).count == 1)
    }

    // MARK: xAI

    // MARK: Qwen

    @Test func qwenKeepsTheKeyInAHeaderAndSeesTheCamera() throws {
        let wire = QwenRealtimeWire()
        let request = wire.request(for: Self.config(.qwen))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.host == "llm-demo.ap-southeast-1.maas.aliyuncs.com")
        #expect(request.url?.path == "/api-ws/v1/realtime")
        #expect(request.url?.query == "model=qwen3.8-omni-flash-realtime")
        #expect(!(request.url?.absoluteString.contains("secret-key") ?? true))
        #expect(wire.inputSampleRate == 16_000)
        #expect(wire.outputSampleRate == 24_000)

        let messages = try wire.setupMessages(for: Self.config(.qwen), resumptionHandle: nil).map(Self.json)
        #expect(messages.count == 3)
        let session = try #require(messages[0]["session"] as? [String: Any])
        let audioSettings = session["audio"] as? [String: [String: String]]
        #expect(audioSettings?["output"]?["voice"] == "Tina")
        #expect(session["instructions"] as? String == "Be brief.")
        #expect(session["input_audio_format"] as? String == "pcm")
        #expect(session["modalities"] as? [String] == ["text", "audio"])
        // Tools go alone, so a rejection can't drop the voice settings.
        let tools = try #require((messages[1]["session"] as? [String: Any])?["tools"] as? [[String: Any]])
        #expect(tools.map { $0["name"] as? String } == ["log_water", "get_app_state"])
        let transcription = (messages[2]["session"] as? [String: Any])?["input_audio_transcription"] as? [String: Any]
        #expect(transcription?["model"] as? String == "qwen3-asr-flash-realtime")

        // Qwen takes images only after some audio.
        #expect(wire.imageMessages(Data([1, 2, 3])).isEmpty)
        _ = wire.audioMessage(Data([0, 1]))
        let image = try Self.json(try #require(wire.imageMessages(Data([1, 2, 3])).first))
        #expect(image["type"] as? String == "input_image_buffer.append")
        #expect(image["image"] as? String == Data([1, 2, 3]).base64EncodedString())
        #expect(wire.imageMessages(Data(count: QwenRealtimeWire.maxImageBytes + 1)).isEmpty)
        #expect(LiveProvider.qwen.cameraFrames == .continuous)

        let output = try Self.json(try #require(wire.toolResponseMessages([
            LiveToolResponse(id: "call_1", name: "log_water", outputJSON: #"{"ok":true}"#),
        ]).first))
        let item = try #require(output["item"] as? [String: Any])
        #expect(item["type"] as? String == "function_call_output")
        #expect(item["call_id"] as? String == "call_1")
        #expect(wire.needsResponseCreate)
        #expect(wire.truncateMessage(itemID: "item-1", audioEndMs: 10) == nil)

        #expect(wire.parse(#"{"type":"response.created","response":{"id":"r1"}}"#) == [.responseStarted])
        #expect(wire.parse(#"{"type":"response.function_call_arguments.done","response_id":"r1","call_id":"call_2","name":"log_water","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}"#).isEmpty)
        #expect(wire.parse(#"{"type":"response.done","response":{"id":"r1","status":"completed"}}"#) == [
            .toolCalls([LiveToolCall(id: "call_2", name: "log_water", argumentsJSON: #"{"amount":250,"unit":"ml"}"#)]),
            .responseDone(hasToolCalls: true),
        ])
        let audio = wire.parse(#"{"type":"response.audio.delta","delta":"AAE="}"#)
        #expect(audio == [.audio(Data([0, 1]), itemID: nil)])
    }

    @Test func qwenDoesNotAttachAKeyToAnInvalidWorkspace() {
        for workspace in ["", "example.com", "a/redirect", "host@other.com", "-invalid", String(repeating: "a", count: 64)] {
            let request = QwenRealtimeWire().request(for: Self.config(.qwen, qwenWorkspace: workspace))
            #expect(!QwenRealtimeWire.isValidWorkspace(workspace))
            #expect(request.url?.host == nil)
            #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
        }
    }

    @Test func extendedThinkingRequestUsesNonBlockingToolsOnlyForByok() throws {
        let model = "gemini-3.8-live-extended-thinking"
        let setup = GeminiLiveWire.setup(for: Self.config(.gemini, model: model), resumptionHandle: nil)
        let generation = try #require(setup["generationConfig"] as? [String: Any])
        #expect((generation["thinkingConfig"] as? [String: String])?["thinkingLevel"] == "LOW")
        let tools = try #require(setup["tools"] as? [[String: [[String: Any]]]])
        #expect(tools.first?["functionDeclarations"]?.first?["behavior"] as? String == "NON_BLOCKING")
        let hosted = GeminiLiveWire.setup(for: Self.config(.hosted, model: model), resumptionHandle: nil)
        #expect((hosted["generationConfig"] as? [String: Any])?["thinkingConfig"] == nil)
    }

    // MARK: GPT-Live

    @Test func gptLiveStartsWithResponsesDelegationAndTakesNoImages() throws {
        let wire = GPTLiveWire()
        let request = wire.request(for: Self.config(.gptLive))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.absoluteString == "wss://api.openai.com/v1/live/sessions")
        #expect(wire.inputSampleRate == 24_000)

        let start = try Self.json(try #require(wire.setupMessages(for: Self.config(.gptLive), resumptionHandle: nil).first))
        #expect(start["type"] as? String == "session.start")
        let session = try #require(start["session"] as? [String: Any])
        #expect(session["model"] as? String == "gpt-live-1")
        #expect((session["audio"] as? [String: Any]).flatMap { $0["output"] as? [String: Any] }?["voice"] as? String == "quartz")
        let delegation = try #require(session["delegation"] as? [String: Any])
        #expect(delegation["type"] as? String == "responses")
        let backend = try #require(delegation["responses"] as? [String: Any])
        #expect(backend["model"] as? String == GPTLiveWire.backendModel)
        #expect((backend["tools"] as? [[String: Any]])?.count == 2)
        #expect(backend["tool_choice"] as? String == "auto")

        let audio = try Self.json(wire.audioMessage(Data([0, 1])))
        #expect(audio["type"] as? String == "session.input_audio.append")
        #expect(wire.imageMessages(Data([1])).isEmpty)
        #expect(LiveProvider.gptLive.cameraFrames == .none)
        #expect(LiveProvider.gptLive.keyProvider == .openai)

        let output = try Self.json(try #require(wire.toolResponseMessages([
            LiveToolResponse(id: "call_1", name: "log_water", outputJSON: #"{"ok":true}"#),
        ]).first))
        #expect(output["type"] as? String == "response.item.create")
        #expect((output["item"] as? [String: Any])?["call_id"] as? String == "call_1")
        let text = try Self.json(try #require(wire.contextTextMessages("Saved.").first))
        #expect(text["type"] as? String == "response.item.create")
    }

    @Test func gptLiveReadsBackendCallsAndEndsTurnsWhenTheUserSpeaks() throws {
        let wire = GPTLiveWire()
        #expect(wire.parse(#"{"type":"session.started","session":{"id":"sess_1"}}"#) == [.ready])
        #expect(wire.parse(#"{"type":"session.output_audio.delta","delta":"AAE=","start_ms":0,"end_ms":100}"#)
            == [.audio(Data([0, 1]), itemID: nil)])
        #expect(wire.parse(#"{"type":"session.output_transcript.delta","delta":"Logging it."}"#) == [.assistantText("Logging it.")])
        #expect(wire.parse(#"{"type":"session.input_transcript.delta","delta":"thanks"}"#)
            == [.turnComplete, .userSpeechStarted, .userText("thanks", replacing: false)])
        #expect(wire.parse(#"{"type":"session.input_transcript.delta","delta":" a lot"}"#) == [.userText(" a lot", replacing: false)])

        #expect(wire.parse(#"{"type":"response.event","delegation_id":"d1","event":{"type":"response.created","response":{"id":"resp_1"}}}"#)
            == [.responseStarted])
        #expect(wire.parse(#"{"type":"response.event","delegation_id":"d1","event":{"type":"response.output_item.done","item":{"type":"function_call","status":"completed","call_id":"call_9","name":"log_water","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}}}"#).isEmpty)
        // Backend text is spoken by GPT-Live, so it isn't shown twice.
        #expect(wire.parse(#"{"type":"response.event","delegation_id":"d1","event":{"type":"response.output_text.delta","delta":"Done"}}"#).isEmpty)
        #expect(wire.parse(#"{"type":"response.event","delegation_id":"d1","event":{"type":"response.completed","response":{"id":"resp_1","status":"completed"}}}"#) == [
            .toolCalls([LiveToolCall(id: "call_9", name: "log_water", argumentsJSON: #"{"amount":250,"unit":"ml"}"#)]),
            .responseDone(hasToolCalls: true),
        ])
        // A backend answer without tools still doesn't end the spoken turn.
        _ = wire.parse(#"{"type":"response.event","delegation_id":"d2","event":{"type":"response.created","response":{"id":"resp_2"}}}"#)
        #expect(wire.parse(#"{"type":"response.event","delegation_id":"d2","event":{"type":"response.completed","response":{"id":"resp_2"}}}"#)
            == [.responseDone(hasToolCalls: true)])
    }

    @Test func xaiDeclaresToolsAndTakesNoImages() throws {
        let wire = XAIVoiceWire()
        let request = wire.request(for: Self.config(.xai))
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer secret-key")
        #expect(request.url?.host == "api.x.ai")

        let messages = try wire.setupMessages(for: Self.config(.xai), resumptionHandle: nil).map(Self.json)
        let session = try #require(messages[0]["session"] as? [String: Any])
        #expect(session["voice"] as? String == "ara")
        #expect((session["tools"] as? [[String: Any]])?.count == 2)
        // tool_choice goes alone, so a rejection can't drop the voice settings.
        #expect((messages[1]["session"] as? [String: Any])?.keys.sorted() == ["tool_choice"])

        #expect(wire.imageMessages(Data([1])).isEmpty)
        #expect(LiveProvider.xai.cameraFrames == .none)
        let call = wire.parse(#"{"type":"response.function_call_arguments.done","call_id":"c1","name":"end_conversation","arguments":""}"#)
        #expect(call.isEmpty)
        #expect(wire.parse(#"{"type":"response.done","response":{"status":"completed"}}"#) == [
            .toolCalls([LiveToolCall(id: "c1", name: "end_conversation", argumentsJSON: "{}")]), .responseDone(hasToolCalls: true),
        ])
        #expect(wire.parse(#"{"type":"conversation.item.input_audio_transcription.updated","transcript":"log a banana"}"#)
            == [.userText("log a banana", replacing: true)])
    }

    @Test func realtimeRecoversFunctionNamesAndEmitsOneCallOnlyAfterCompletion() {
        let wire = OpenAIRealtimeWire()
        _ = wire.parse(#"{"type":"response.created","response":{"id":"r"}}"#)
        #expect(wire.parse(#"{"type":"response.output_item.added","response_id":"r","item":{"id":"item","type":"function_call","call_id":"call","name":"log_water"}}"#).isEmpty)
        #expect(wire.parse(#"{"type":"response.function_call_arguments.done","response_id":"r","item_id":"item","call_id":"call","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}"#).isEmpty)
        #expect(wire.parse(#"{"type":"response.output_item.done","response_id":"r","item":{"id":"item","type":"function_call","call_id":"call","name":"log_water","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}}"#).isEmpty)
        let completion = #"{"type":"response.done","response":{"id":"r","status":"completed","output":[]}}"#
        #expect(wire.parse(completion) == [
            .toolCalls([LiveToolCall(id: "call", name: "log_water", argumentsJSON: #"{"amount":250,"unit":"ml"}"#)]),
            .responseDone(hasToolCalls: true),
        ])
        #expect(wire.parse(completion).isEmpty)
    }

    @Test(arguments: ["cancelled", "failed", "incomplete", "interrupted"])
    func realtimeDiscardsCallsFromUnsuccessfulOrInterruptedResponses(status: String) {
        let wire = OpenAIRealtimeWire()
        _ = wire.parse(#"{"type":"response.created","response":{"id":"r"}}"#)
        _ = wire.parse(#"{"type":"response.function_call_arguments.done","response_id":"r","call_id":"call","name":"delete_food_entry","arguments":"{}"}"#)
        if status == "interrupted" { _ = wire.parse(#"{"type":"input_audio_buffer.speech_started"}"#) }
        let completion = LiveJSON.string(["type": "response.done", "response": ["id": "r", "status": status == "interrupted" ? "completed" : status]])
        let events = wire.parse(completion)
        #expect(!events.contains { if case .toolCalls = $0 { return true }; return false })
        #expect(events.last == .responseDone(hasToolCalls: false, successful: false))
        _ = wire.parse(#"{"type":"response.created","response":{"id":"next"}}"#)
        #expect(wire.parse(#"{"type":"response.done","response":{"id":"next","status":"completed","output":[]}}"#) == [.responseDone(hasToolCalls: false)])
    }

    @Test(arguments: ["completed", "cancelled", "incomplete", "failed", "interrupted", "in_progress"])
    func realtimePreservesWhetherTheResponseCompletedSuccessfully(status: String) {
        let wire = OpenAIRealtimeWire()
        let message = LiveJSON.string(["type": "response.done", "response": ["id": "preview", "status": status]])
        #expect(wire.parse(message).last == .responseDone(hasToolCalls: false, successful: status == "completed"))
    }

    @Test func cameraFramePoliciesFollowTheContract() {
        #expect(LiveProvider.hosted.cameraFrames == .continuous)
        #expect(LiveProvider.gemini.cameraFrames == .continuous)
        #expect(LiveProvider.openai.cameraFrames == .perTurn)
        #expect(LiveProvider.xai.cameraFrames == .none)
        #expect(VoiceAssistantCamera.frameMaximumSide <= 768)
    }

    // MARK: Socket

    @Test func socketReadsBinaryAndTextFrames() throws {
        let text = #"{"setupComplete":{}}"#
        #expect(try URLSessionLiveSocket.text(from: .string(text)) == text)
        #expect(try URLSessionLiveSocket.text(from: .data(Data(text.utf8))) == text)
    }

    @Test func socketReportsRefusedUpgradesAndServerCloses() throws {
        let url = try #require(URL(string: "wss://fud-ai.app/api/hosted-ai/v1/live"))
        let refused = HTTPURLResponse(url: url, statusCode: 402, httpVersion: nil, headerFields: nil)
        let paymentError = URLSessionLiveSocket.classify(URLError(.badServerResponse), response: refused, closeCode: 0, closeReason: nil)
        #expect((paymentError as? LiveSocketError)?.endReason == .refused(statusCode: 402))

        let upgraded = HTTPURLResponse(url: url, statusCode: 101, httpVersion: nil, headerFields: nil)
        let minutes = URLSessionLiveSocket.classify(URLError(.networkConnectionLost), response: upgraded, closeCode: 4402, closeReason: nil)
        #expect((minutes as? LiveSocketError)?.endReason == .closedByServer(code: 4402, reason: nil))

        let normal = URLSessionLiveSocket.classify(URLError(.networkConnectionLost), response: upgraded, closeCode: 1000, closeReason: Data("bye".utf8))
        #expect((normal as? LiveSocketError)?.endReason == .closedByServer(code: 1000, reason: "bye"))

        let network = URLSessionLiveSocket.classify(URLError(.notConnectedToInternet), response: nil, closeCode: 0, closeReason: nil)
        #expect(network is URLError)
    }

    // MARK: Session

    @Test func sessionSendsSetupFirstAndQueuesInputUntilGeminiIsReady() async throws {
        let socket = FakeLiveSocket()
        let events = EventRecorder()
        let session = LiveSession(config: Self.config(.gemini), wire: GeminiLiveWire(), makeSocket: { _ in socket }) { event in
            events.append(event)
        }
        session.connect()
        session.send(toolResponses: [LiveToolResponse(id: "1", name: "get_app_state", outputJSON: #"{"ok":true}"#)])
        try await socket.waitForSent(count: 1)
        #expect(socket.sent.count == 1)
        #expect(socket.sent.first?.contains("\"setup\"") == true)

        socket.deliver(#"{"setupComplete":{}}"#)
        try await socket.waitForSent(count: 2)
        #expect(socket.sent[1].contains("toolResponse"))
        try await events.waitFor { $0.contains(.ready) }

        socket.deliver(#"{"toolCall":{"functionCalls":[{"id":"7","name":"close_camera","args":{}}]}}"#)
        try await events.waitFor { $0.contains(.toolCalls([LiveToolCall(id: "7", name: "close_camera", argumentsJSON: "{}")])) }
        session.close()
    }

    @Test func sessionReportsWhyTheServerClosed() async throws {
        let socket = FakeLiveSocket()
        let events = EventRecorder()
        let session = LiveSession(config: Self.config(.hosted, credential: .hostedUser("u")), wire: HostedLiveWire(), makeSocket: { _ in socket }) { event in
            events.append(event)
        }
        session.connect()
        socket.fail(with: LiveSocketError.closed(code: 4408, reason: nil))
        try await events.waitFor { $0.contains(.closed(.closedByServer(code: 4408, reason: nil))) }
        session.close()
    }

    @Test(arguments: [LiveProvider.openai, .xai, .qwen])
    func realtimeSessionsWaitForConfigurationBeforeSendingMicrophoneAudio(_ provider: LiveProvider) async throws {
        let socket = FakeLiveSocket()
        let events = EventRecorder()
        let wire = provider.wire
        let config = Self.config(provider)
        let setupCount = wire.setupMessages(for: config, resumptionHandle: nil).count
        let session = LiveSession(config: config, wire: wire, makeSocket: { _ in socket }) { events.append($0) }
        session.connect()
        session.send(audio: Data([0, 1]))
        try await socket.waitForSent(count: setupCount)
        #expect(socket.sent.count == setupCount)
        #expect(!socket.sent.contains { $0.contains("input_audio_buffer.append") })
        #expect(wire.waitsForSetupAcknowledgement)
        #expect(wire.parse(#"{"type":"session.created"}"#).isEmpty)

        socket.deliver(#"{"type":"session.updated","session":{}}"#)
        try await socket.waitForSent(count: setupCount + 1)
        #expect(socket.sent.last?.contains("input_audio_buffer.append") == true)
        try await events.waitFor { $0.contains(.ready) }
        session.close()
    }

    @Test func geminiReconnectTimesOutWithoutANewSetupAcknowledgement() async throws {
        let sockets = FakeLiveSocketSequence()
        let deadlines = ManualLiveSetupDeadlines()
        let events = EventRecorder()
        let session = LiveSession(
            config: Self.config(.hosted, credential: .hostedUser("u")), wire: HostedLiveWire(),
            makeSocket: { _ in sockets.next() }, waitForSetupTimeout: { await deadlines.wait() }
        ) { events.append($0) }
        defer { session.close(); deadlines.fireAll() }
        session.connect()
        try await sockets.first.waitForSent(count: 1)
        try await deadlines.waitForCount(1)
        sockets.first.deliver(#"{"setupComplete":{}}"#)
        try await events.waitFor { $0.contains(.ready) }
        // Relay resumption tokens are opaque and may exceed a native Gemini
        // handle's length; the client must preserve the entire signed token.
        let handle = "fudv1." + String(repeating: "x", count: 8_000)
        sockets.first.deliver(LiveJSON.string(["sessionResumptionUpdate": ["newHandle": handle, "resumable": true]]))
        sockets.first.deliver(#"{"goAway":{"timeLeft":"10s"}}"#)
        try await sockets.second.waitForSent(count: 1)
        try await deadlines.waitForCount(2)
        let sentSetup = try #require(sockets.second.sent.first)
        let setupMessage = try Self.json(sentSetup)
        let setup = try #require(setupMessage["setup"] as? [String: Any])
        #expect((setup["sessionResumption"] as? [String: Any])?["handle"] as? String == handle)
        session.send(audio: Data([0, 1]))
        #expect(sockets.second.sent.count == 1)

        // The retired connection's deadline must not close its replacement.
        deadlines.fire(0)
        #expect(!sockets.second.wasClosed)
        deadlines.fire(1)
        try await events.waitFor { $0.contains { if case .closed = $0 { true } else { false } } }
        #expect(sockets.second.wasClosed)
        #expect(sockets.second.sent.count == 1)
    }

    @Test func reconnectAcknowledgementRetiresItsSetupDeadline() async throws {
        let sockets = FakeLiveSocketSequence()
        let deadlines = ManualLiveSetupDeadlines()
        let events = EventRecorder()
        let session = LiveSession(
            config: Self.config(.gemini), wire: GeminiLiveWire(),
            makeSocket: { _ in sockets.next() }, waitForSetupTimeout: { await deadlines.wait() }
        ) { events.append($0) }
        defer { session.close(); deadlines.fireAll() }
        session.connect()
        try await deadlines.waitForCount(1)
        sockets.first.deliver(#"{"setupComplete":{}}"#)
        try await events.waitFor { $0.contains(.ready) }
        sockets.first.deliver(#"{"goAway":{}}"#)
        try await deadlines.waitForCount(2)
        sockets.second.deliver(#"{"setupComplete":{}}"#)
        try await events.waitFor { $0.filter { $0 == .ready }.count == 2 }
        deadlines.fireAll()
        session.send(contextText: "Still listening")
        try await sockets.second.waitForSent(count: 2)
        #expect(sockets.second.sent.last?.contains("Still listening") == true)
        #expect(!sockets.second.wasClosed)
    }
}

// MARK: - Fakes

/// A socket that records what is sent and hands back scripted server messages.
final class FakeLiveSocket: LiveSocket, @unchecked Sendable {
    private let lock = NSLock()
    private var outbox: [String] = []
    private var inbox: [Result<String, Error>] = []
    private var waiting: CheckedContinuation<String, Error>?
    private var closed = false

    var sent: [String] { lock.withLock { outbox } }
    var wasClosed: Bool { lock.withLock { closed } }

    func send(_ text: String) async throws {
        lock.withLock { outbox.append(text) }
    }

    func receive() async throws -> String {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if closed {
                lock.unlock()
                continuation.resume(throwing: CancellationError())
                return
            }
            if !inbox.isEmpty {
                let next = inbox.removeFirst()
                lock.unlock()
                continuation.resume(with: next)
            } else {
                waiting = continuation
                lock.unlock()
            }
        }
    }

    func close() {
        let pending: CheckedContinuation<String, Error>? = lock.withLock {
            closed = true
            defer { waiting = nil }
            return waiting
        }
        pending?.resume(throwing: CancellationError())
    }

    func deliver(_ text: String) {
        push(.success(text))
    }

    func fail(with error: Error) {
        push(.failure(error))
    }

    private func push(_ result: Result<String, Error>) {
        lock.lock()
        if let waiting {
            self.waiting = nil
            lock.unlock()
            waiting.resume(with: result)
        } else {
            inbox.append(result)
            lock.unlock()
        }
    }

    func waitForSent(count: Int) async throws {
        for _ in 0..<200 where sent.count < count {
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

final class FakeLiveSocketSequence: @unchecked Sendable {
    let first = FakeLiveSocket()
    let second = FakeLiveSocket()
    private let lock = NSLock()
    private var count = 0

    func next() -> FakeLiveSocket {
        lock.withLock {
            defer { count += 1 }
            return count == 0 ? first : second
        }
    }
}

/// Tests advance setup deadlines explicitly, without timing races with CI load.
final class ManualLiveSetupDeadlines: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var waiting: [Int: CheckedContinuation<Void, Never>] = [:]

    func wait() async {
        await withCheckedContinuation { continuation in
            lock.withLock {
                waiting[count] = continuation
                count += 1
            }
        }
    }

    func fire(_ index: Int) {
        lock.withLock { waiting.removeValue(forKey: index) }?.resume()
    }

    func fireAll() {
        let pending = lock.withLock {
            defer { waiting.removeAll() }
            return Array(waiting.values)
        }
        for continuation in pending { continuation.resume() }
    }

    func waitForCount(_ expected: Int) async throws {
        for _ in 0..<200 {
            if lock.withLock({ count >= expected }) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out waiting for setup deadline \(expected)")
    }
}

final class EventRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [LiveEvent] = []

    func append(_ event: LiveEvent) {
        lock.withLock { events.append(event) }
    }

    func waitFor(_ condition: ([LiveEvent]) -> Bool) async throws {
        for _ in 0..<200 {
            if condition(lock.withLock({ events })) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("Timed out waiting for events; got \(lock.withLock { events })")
    }
}
