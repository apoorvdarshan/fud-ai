import Foundation

// The live session runs on background threads (the microphone tap and the socket's
// receive loop), so the types in this folder opt out of the app target's default
// main-actor isolation. Ported from Scowld (same author, MIT) and extended with
// tool calls, the hosted relay and camera policies.

// MARK: - Providers

/// The speech-to-speech services the voice assistant can talk through.
nonisolated enum LiveProvider: String, CaseIterable, Codable, Sendable {
    /// Fud AI's Worker relay to Gemini Live, paid by Plus/Pro voice minutes.
    case hosted
    case gemini
    case openai
    case xai
    /// Alibaba's Qwen Omni Realtime (Model Studio, international region).
    case qwen
    /// OpenAI's GPT-Live, which hands tool use to a backend model. Uses the OpenAI key.
    case gptLive

    /// The provider's name for settings and messages.
    var displayName: String {
        switch self {
        case .hosted: "Fud AI (Plus/Pro)"
        case .gemini: "Gemini"
        case .openai: "OpenAI"
        case .xai: "xAI"
        case .qwen: "Qwen"
        case .gptLive: "OpenAI GPT-Live"
        }
    }

    /// Built-in models; the first is the default. Hosted sessions always use the
    /// Worker's model, whatever the client asks for.
    var models: [String] {
        switch self {
        case .hosted: ["gemini-3.8-live"]
        case .gemini: ["gemini-3.8-live", "gemini-3.8-live-extended-thinking", "gemini-3.1-flash-live-preview"]
        case .openai: ["gpt-realtime-2.1-mini", "gpt-realtime-2.1"]
        case .xai: ["grok-voice-latest", "grok-voice-3.0"]
        case .qwen: ["qwen3.8-omni-flash-realtime"]
        case .gptLive: ["gpt-live-1", "gpt-live-1.1"]
        }
    }

    var defaultModel: String { models[0] }

    /// Built-in voices; the first is the default. Hosted sessions are Gemini Live,
    /// so they share Gemini's voices.
    var voices: [String] {
        switch self {
        case .hosted, .gemini:
            ["Kore", "Puck", "Charon", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr", "Sulafat", "Achird", "Despina", "Gacrux"]
        case .openai:
            ["marin", "cedar", "coral", "sage", "shimmer", "ash", "ballad", "alloy", "echo", "verse"]
        case .xai:
            ["ara", "eve", "rex", "sal", "leo"]
        case .qwen:
            ["Tina", "Cindy", "Raymond", "Katerina", "Ryan", "Mia", "Jennifer", "Aiden", "Sohee", "Lenn"]
        case .gptLive:
            ["quartz", "ripple", "vesper", "willow", "stone", "gleam", "meridian", "bossa", "tempo", "beacon", "delta", "cinder"]
        }
    }

    var defaultVoice: String { voices[0] }

    /// How camera frames reach the model while the assistant's camera is open.
    var cameraFrames: LiveCameraFrames {
        switch self {
        case .hosted, .gemini, .qwen: .continuous
        case .openai: .perTurn
        case .xai, .gptLive: .none
        }
    }

    /// Whether the provider says when a spoken reply ends. GPT-Live doesn't, so its
    /// turns end when the user speaks and its voice preview ends after a quiet moment.
    var reportsTurnEnds: Bool { self != .gptLive }

    /// The provider whose settings (model, voice) this one uses. Hosted sessions
    /// pick from Gemini's voices, so they share Gemini's voice choice.
    var settingsProvider: LiveProvider {
        self == .hosted ? .gemini : self
    }

    var wire: any LiveWire {
        switch self {
        case .hosted: HostedLiveWire()
        case .gemini: GeminiLiveWire()
        case .openai: OpenAIRealtimeWire()
        case .xai: XAIVoiceWire()
        case .qwen: QwenRealtimeWire()
        case .gptLive: GPTLiveWire()
        }
    }
}

/// How camera frames reach the model (the contract's `camera_frames`).
nonisolated enum LiveCameraFrames: String, Sendable {
    /// One JPEG a second while the camera is open (Gemini, Qwen and hosted).
    case continuous
    /// The latest frame with each user turn (OpenAI).
    case perTurn = "per_turn"
    /// The model sees no images (xAI, GPT-Live); it asks the user to say when the food is in frame.
    case none
}

// MARK: - Session settings and events

/// What a session needs to connect: who to talk to, how, and with which tools.
nonisolated struct LiveSessionConfig: Sendable, Equatable {
    enum Credential: Sendable, Equatable {
        /// The user's own provider key (sent in a header, never the URL).
        case apiKey(String)
        /// The RevenueCat app user id the Worker verifies the plan for.
        case hostedUser(String)
    }

    let provider: LiveProvider
    let model: String
    let voice: String
    let credential: Credential
    let instructions: String
    let tools: [LiveToolDeclaration]
    let qwenWorkspace: String
    let geminiBargeIn: Bool

    init(provider: LiveProvider, model: String, voice: String, credential: Credential,
         instructions: String, tools: [LiveToolDeclaration], qwenWorkspace: String = "", geminiBargeIn: Bool = true) {
        self.provider = provider
        self.model = model
        self.voice = voice
        self.credential = credential
        self.instructions = instructions
        self.tools = tools
        self.qwenWorkspace = qwenWorkspace
        self.geminiBargeIn = geminiBargeIn
    }

    var apiKey: String {
        if case .apiKey(let key) = credential { return key }
        return ""
    }

    var hostedUserID: String {
        if case .hostedUser(let id) = credential { return id }
        return ""
    }
}

/// A function the model may call, in the provider-neutral shape of `tools.json`.
nonisolated struct LiveToolDeclaration: Sendable, Equatable {
    let name: String
    let description: String
    /// JSON Schema of the arguments as JSON text; nil when the tool takes none.
    let parametersJSON: String?

    /// The arguments schema, or nil for a tool without parameters.
    var parameters: [String: Any]? {
        guard let parametersJSON else { return nil }
        return LiveJSON.object(parametersJSON)
    }
}

/// One function call the model made.
nonisolated struct LiveToolCall: Sendable, Equatable {
    /// Gemini's call id or OpenAI/xAI's `call_id`; the response must echo it.
    let id: String
    let name: String
    /// The arguments as a JSON object text ("{}" when there are none).
    let argumentsJSON: String

    var arguments: [String: Any] {
        LiveJSON.object(argumentsJSON) ?? [:]
    }
}

/// The result of one function call, as a JSON object text.
nonisolated struct LiveToolResponse: Sendable, Equatable {
    let id: String
    let name: String
    let outputJSON: String
}

/// What a provider says during a live session, in one shared vocabulary.
nonisolated enum LiveEvent: Equatable, Sendable {
    /// The session is configured and accepts audio and text.
    case ready
    /// Part of the assistant's reply: 16-bit little-endian mono PCM at the wire's output rate.
    /// OpenAI names the reply item, so playback can be cut at the right point on interruption.
    case audio(Data, itemID: String?)
    /// Part of the transcript of what the assistant is saying.
    case assistantText(String)
    /// The transcript of what the user said: a part to append, or the whole utterance so far.
    case userText(String, replacing: Bool)
    /// The user started speaking (OpenAI and xAI report this; it also ends any reply in progress).
    case userSpeechStarted
    /// The provider stopped its reply because the user spoke (Gemini).
    case interrupted
    /// The assistant finished its turn (Gemini `turnComplete`).
    case turnComplete
    /// OpenAI/xAI started generating a response.
    case responseStarted
    /// OpenAI/xAI finished a response. A response that ended in function calls is
    /// not the end of the turn: the reply continues once the results are in.
    /// `successful` is false for cancelled, incomplete or interrupted responses.
    case responseDone(hasToolCalls: Bool, successful: Bool = true)
    /// The model wants these functions run.
    case toolCalls([LiveToolCall])
    /// Gemini: the user interrupted, so these calls should be abandoned.
    case toolCallCancellation([String])
    /// Hosted: voice time left on the user's plan and credits, in seconds.
    case hostedSecondsRemaining(Int)
    /// Hosted: the Worker is about to close the session. Codes include
    /// `voice_quota_exceeded`, `session_limit`, `rate_limited`, `voice_unavailable`,
    /// `upstream_error`, `invalid_setup`, `invalid_message` and `setup_timeout`.
    case hostedError(code: String, detail: String?)
    /// Gemini: a handle that resumes this session on a new connection.
    case resumptionHandle(String)
    /// Gemini: this connection will close soon; reconnect with the latest handle.
    case goAway
    /// The provider reported a problem; the session continues unless it also closes.
    case error(String, code: String?)
    /// The connection ended. Nil for a normal end the client asked for.
    case closed(LiveEndReason?)
}

/// Why a connection ended without the client closing it.
nonisolated enum LiveEndReason: Equatable, Sendable {
    /// The server refused the WebSocket upgrade with this HTTP status
    /// (the hosted relay: 401, 402, 403, 429, 503; own keys: 401/403 for a bad key).
    case refused(statusCode: Int)
    /// The server closed the socket with this code (hosted: 4402 out of minutes,
    /// 4408 session limit, 1011 upstream failure) and an optional reason.
    case closedByServer(code: Int, reason: String?)
    /// The network or another error ended the connection.
    case failed(String)

    /// A clean close (the hosted relay passes Gemini's clean close on as 1000).
    static let normalCloseCode = 1000
    static let outOfMinutesCloseCode = 4402
    static let sessionLimitCloseCode = 4408
    static let upstreamFailureCloseCode = 1011
}

// MARK: - Wire protocol

/// How one provider frames a live session on the wire.
nonisolated protocol LiveWire: Sendable {
    /// Sample rate of the microphone audio the provider expects (16-bit mono PCM).
    var inputSampleRate: Double { get }
    /// Sample rate of the reply audio the provider sends (16-bit mono PCM).
    var outputSampleRate: Double { get }
    /// Whether the session is ready only after the provider acknowledges the setup.
    var waitsForSetupAcknowledgement: Bool { get }
    /// OpenAI/xAI: function results need an explicit `response.create`, which the
    /// provider refuses while another response is still being generated.
    var needsResponseCreate: Bool { get }

    func request(for config: LiveSessionConfig) -> URLRequest
    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String]
    func audioMessage(_ pcm16: Data) -> String
    /// A camera frame on its own (no reply requested). Empty if the provider takes no images.
    func imageMessages(_ jpeg: Data) -> [String]
    /// Text the app adds to the conversation as the user (card taps), asking for a
    /// reply. OpenAI/xAI need a separate `responseCreateMessage` afterwards.
    func contextTextMessages(_ text: String) -> [String]
    /// The results of function calls.
    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String]
    /// Asks OpenAI/xAI for a response; nil for Gemini, which continues by itself.
    func responseCreateMessage() -> String?
    /// Tells the provider how much of a reply the user actually heard, or nil if unsupported.
    func truncateMessage(itemID: String, audioEndMs: Int) -> String?
    func parse(_ message: String) -> [LiveEvent]
}

// MARK: - Gemini Live

/// Gemini Live API over a raw WebSocket (BidiGenerateContent).
nonisolated struct GeminiLiveWire: LiveWire {
    let inputSampleRate = 16_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = false

    static let endpoint = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    func request(for config: LiveSessionConfig) -> URLRequest {
        var request = URLRequest(url: URL(string: Self.endpoint)!)
        // The key goes in a header, never the URL, so it cannot end up in logged URLs.
        request.setValue(config.apiKey, forHTTPHeaderField: "x-goog-api-key")
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        [LiveJSON.string(["setup": Self.setup(for: config, resumptionHandle: resumptionHandle)])]
    }

    /// The `setup` object, shared with the hosted relay (which replaces `model`).
    static func setup(for config: LiveSessionConfig, resumptionHandle: String?) -> [String: Any] {
        let model = config.model.hasPrefix("models/") ? config.model : "models/\(config.model)"
        let extendedThinking = config.provider == .gemini && model == "models/gemini-3.8-live-extended-thinking"
        var generation: [String: Any] = [
            "responseModalities": ["AUDIO"],
            "speechConfig": ["voiceConfig": ["prebuiltVoiceConfig": ["voiceName": config.voice]]],
        ]
        if extendedThinking { generation["thinkingConfig"] = ["thinkingLevel": "LOW"] }
        var resumption: [String: Any] = [:]
        if let resumptionHandle { resumption["handle"] = resumptionHandle }
        let instructions = """
        Speak in one consistent voice throughout the session. Keep the configured voice across replies and languages. Do not imitate the user or other speakers, even when quoting them. Ignore background sounds that do not address you.

        \(config.instructions)
        """
        var setup: [String: Any] = [
            "model": model,
            "generationConfig": generation,
            "systemInstruction": ["parts": [["text": VoiceAssistantInstructions.limitedToRelayLength(instructions)]]],
            "realtimeInputConfig": [
                "automaticActivityDetection": [
                    "startOfSpeechSensitivity": "START_SENSITIVITY_LOW",
                    "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
                    "prefixPaddingMs": 240,
                    "silenceDurationMs": 700,
                ],
                "activityHandling": config.geminiBargeIn ? "START_OF_ACTIVITY_INTERRUPTS" : "NO_INTERRUPTION",
            ],
            "inputAudioTranscription": [String: Any](),
            "outputAudioTranscription": [String: Any](),
            // Without compression an audio session ends after 15 minutes, and one with
            // camera frames after 2.
            "contextWindowCompression": ["slidingWindow": [String: Any]()],
            "sessionResumption": resumption,
        ]
        if !config.tools.isEmpty {
            setup["tools"] = [["functionDeclarations": config.tools.map { tool in
                var declaration = functionDeclaration(tool)
                if extendedThinking { declaration["behavior"] = "NON_BLOCKING" }
                return declaration
            }]]
        }
        return setup
    }

    /// A declaration without arguments leaves out `parameters`: Gemini rejects an
    /// object schema with no properties in some models.
    static func functionDeclaration(_ tool: LiveToolDeclaration) -> [String: Any] {
        var declaration: [String: Any] = ["name": tool.name, "description": tool.description]
        if let parameters = tool.parameters,
           !((parameters["properties"] as? [String: Any]) ?? [:]).isEmpty {
            declaration["parameters"] = parameters
        }
        return declaration
    }

    func audioMessage(_ pcm16: Data) -> String {
        LiveJSON.string(["realtimeInput": ["audio": [
            "data": pcm16.base64EncodedString(),
            "mimeType": "audio/pcm;rate=16000",
        ]]])
    }

    func imageMessages(_ jpeg: Data) -> [String] {
        [LiveJSON.string(["realtimeInput": ["video": [
            "data": jpeg.base64EncodedString(),
            "mimeType": "image/jpeg",
        ]]])]
    }

    func contextTextMessages(_ text: String) -> [String] {
        [LiveJSON.string(["clientContent": [
            "turns": [["role": "user", "parts": [["text": text]]]],
            "turnComplete": true,
        ]])]
    }

    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] {
        guard !responses.isEmpty else { return [] }
        let functionResponses: [[String: Any]] = responses.map { response in
            [
                "id": response.id,
                "name": response.name,
                "response": LiveJSON.object(response.outputJSON) ?? ["ok": false],
            ]
        }
        return [LiveJSON.string(["toolResponse": ["functionResponses": functionResponses]])]
    }

    func responseCreateMessage() -> String? {
        nil
    }

    func truncateMessage(itemID: String, audioEndMs: Int) -> String? {
        nil
    }

    func parse(_ message: String) -> [LiveEvent] {
        guard let json = LiveJSON.object(message) else { return [] }
        return Self.events(from: json)
    }

    static func events(from json: [String: Any]) -> [LiveEvent] {
        var events: [LiveEvent] = []
        if json["setupComplete"] != nil {
            events.append(.ready)
        }
        if let content = json["serverContent"] as? [String: Any] {
            let parts = (content["modelTurn"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
            for part in parts {
                guard let inline = part["inlineData"] as? [String: Any],
                      (inline["mimeType"] as? String)?.hasPrefix("audio/") == true,
                      let base64 = inline["data"] as? String,
                      let audio = Data(base64Encoded: base64) else { continue }
                events.append(.audio(audio, itemID: nil))
            }
            if let text = (content["inputTranscription"] as? [String: Any])?["text"] as? String, !text.isEmpty {
                events.append(.userText(text, replacing: false))
            }
            if let text = (content["outputTranscription"] as? [String: Any])?["text"] as? String, !text.isEmpty {
                events.append(.assistantText(text))
            }
            if content["interrupted"] as? Bool == true {
                events.append(.interrupted)
            }
            if content["turnComplete"] as? Bool == true {
                events.append(.turnComplete)
            }
        }
        if let toolCall = json["toolCall"] as? [String: Any] {
            let calls: [LiveToolCall] = (toolCall["functionCalls"] as? [[String: Any]] ?? []).compactMap { call in
                guard let name = call["name"] as? String, !name.isEmpty else { return nil }
                let arguments = call["args"] as? [String: Any] ?? [:]
                return LiveToolCall(
                    id: (call["id"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? name,
                    name: name,
                    argumentsJSON: LiveJSON.string(arguments)
                )
            }
            if !calls.isEmpty {
                events.append(.toolCalls(calls))
            }
        }
        if let cancellation = json["toolCallCancellation"] as? [String: Any],
           let ids = cancellation["ids"] as? [String], !ids.isEmpty {
            events.append(.toolCallCancellation(ids))
        }
        if let update = json["sessionResumptionUpdate"] as? [String: Any],
           update["resumable"] as? Bool != false,
           let handle = update["newHandle"] as? String, !handle.isEmpty {
            events.append(.resumptionHandle(handle))
        }
        if json["goAway"] != nil {
            events.append(.goAway)
        }
        if let error = json["error"] as? [String: Any] {
            events.append(.error(error["message"] as? String ?? "Gemini reported an error.", code: (error["status"] ?? error["code"]).map { "\($0)" }))
        }
        return events
    }
}

// MARK: - Fud AI hosted relay

/// Fud AI's Worker relay: the Gemini Live wire protocol to the Worker, which
/// checks the plan and voice balance before upgrading, replaces the model, and
/// adds its own `fudVoice` messages.
nonisolated struct HostedLiveWire: LiveWire {
    let inputSampleRate = 16_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = false

    static let endpoint = "wss://fud-ai.app/api/hosted-ai/v1/live"
    static let userIDHeader = "X-Fud-User-Id"
    static let platformHeader = "X-Fud-Platform"

    private let gemini = GeminiLiveWire()

    func request(for config: LiveSessionConfig) -> URLRequest {
        var request = URLRequest(url: URL(string: Self.endpoint)!)
        request.setValue(config.hostedUserID, forHTTPHeaderField: Self.userIDHeader)
        request.setValue("ios", forHTTPHeaderField: Self.platformHeader)
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        gemini.setupMessages(for: config, resumptionHandle: resumptionHandle)
    }

    func audioMessage(_ pcm16: Data) -> String { gemini.audioMessage(pcm16) }
    func imageMessages(_ jpeg: Data) -> [String] { gemini.imageMessages(jpeg) }
    func contextTextMessages(_ text: String) -> [String] { gemini.contextTextMessages(text) }
    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] { gemini.toolResponseMessages(responses) }
    func responseCreateMessage() -> String? { nil }
    func truncateMessage(itemID: String, audioEndMs: Int) -> String? { nil }

    func parse(_ message: String) -> [LiveEvent] {
        guard let json = LiveJSON.object(message) else { return [] }
        var events: [LiveEvent] = []
        if let voice = json["fudVoice"] as? [String: Any] {
            if let seconds = (voice["secondsRemaining"] as? NSNumber)?.intValue {
                events.append(.hostedSecondsRemaining(max(0, seconds)))
            }
            if let error = voice["error"] as? String, !error.isEmpty {
                let detail = (voice["detail"] as? String) ?? voice["upstreamCode"].map { "upstream \($0)" }
                events.append(.hostedError(code: error, detail: detail))
            }
        }
        return events + GeminiLiveWire.events(from: json)
    }
}

// MARK: - OpenAI Realtime

/// OpenAI's Realtime API (GA event names) over a WebSocket.
nonisolated struct OpenAIRealtimeWire: LiveWire {
    private let events = RealtimeLiveEvents()
    let inputSampleRate = 24_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = true

    /// Transcribes what the user says so captions and Coach get the user's words.
    static let transcriptionModel = "gpt-transcribe"

    func request(for config: LiveSessionConfig) -> URLRequest {
        var components = URLComponents(string: "wss://api.openai.com/v1/realtime")!
        components.queryItems = [URLQueryItem(name: "model", value: config.model)]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        let session: [String: Any] = [
            "type": "realtime",
            "model": config.model,
            "instructions": config.instructions,
            "output_modalities": ["audio"],
            "audio": [
                "input": [
                    "format": ["type": "audio/pcm", "rate": 24_000],
                    "turn_detection": ["type": "semantic_vad", "create_response": true, "interrupt_response": true],
                ],
                "output": [
                    "format": ["type": "audio/pcm", "rate": 24_000],
                    "voice": config.voice,
                ],
            ],
        ]
        // Tools and transcription go in their own updates, so a problem with either
        // can't reject the voice and turn-taking settings with it.
        var messages = [LiveJSON.string(["type": "session.update", "session": session])]
        if !config.tools.isEmpty {
            messages.append(LiveJSON.string(["type": "session.update", "session": [
                "type": "realtime",
                "tools": config.tools.map(Self.functionTool),
                "tool_choice": "auto",
            ]]))
        }
        messages.append(LiveJSON.string(["type": "session.update", "session": [
            "type": "realtime",
            "audio": ["input": ["transcription": ["model": Self.transcriptionModel]]],
        ]]))
        return messages
    }

    /// `{type: "function", name, description, parameters}`, shared with xAI.
    static func functionTool(_ tool: LiveToolDeclaration) -> [String: Any] {
        [
            "type": "function",
            "name": tool.name,
            "description": tool.description,
            "parameters": tool.parameters ?? ["type": "object", "properties": [String: Any]()],
        ]
    }

    func audioMessage(_ pcm16: Data) -> String {
        LiveJSON.string(["type": "input_audio_buffer.append", "audio": pcm16.base64EncodedString()])
    }

    func imageMessages(_ jpeg: Data) -> [String] {
        let content: [[String: Any]] = [["type": "input_image", "image_url": "data:image/jpeg;base64,\(jpeg.base64EncodedString())"]]
        return [LiveJSON.string(["type": "conversation.item.create", "item": ["type": "message", "role": "user", "content": content]])]
    }

    func contextTextMessages(_ text: String) -> [String] {
        Self.userTextItem(text)
    }

    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] {
        Self.functionCallOutputs(responses)
    }

    func responseCreateMessage() -> String? {
        LiveJSON.string(["type": "response.create"])
    }

    func truncateMessage(itemID: String, audioEndMs: Int) -> String? {
        LiveJSON.string([
            "type": "conversation.item.truncate",
            "item_id": itemID,
            "content_index": 0,
            "audio_end_ms": max(0, audioEndMs),
        ])
    }

    func parse(_ message: String) -> [LiveEvent] {
        events.parse(message)
    }

    static func userTextItem(_ text: String) -> [String] {
        let content: [[String: Any]] = [["type": "input_text", "text": text]]
        return [LiveJSON.string(["type": "conversation.item.create", "item": ["type": "message", "role": "user", "content": content]])]
    }

    static func functionCallOutputs(_ responses: [LiveToolResponse]) -> [String] {
        responses.map { response in
            LiveJSON.string(["type": "conversation.item.create", "item": [
                "type": "function_call_output",
                "call_id": response.id,
                "output": response.outputJSON,
            ]])
        }
    }

    /// Server events shared by OpenAI and xAI, which follows the same Realtime shape.
    /// Alternative names that either provider uses are accepted too.
    static func parseRealtimeEvent(_ message: String) -> [LiveEvent] {
        guard let json = LiveJSON.object(message), let type = json["type"] as? String else { return [] }
        switch type {
        case "session.updated":
            return [.ready]
        case "response.output_audio.delta", "response.audio.delta":
            guard let base64 = json["delta"] as? String, let audio = Data(base64Encoded: base64) else { return [] }
            return [.audio(audio, itemID: json["item_id"] as? String)]
        case "response.output_audio_transcript.delta", "response.audio_transcript.delta",
             "response.output_text.delta", "response.text.delta":
            guard let delta = json["delta"] as? String, !delta.isEmpty else { return [] }
            return [.assistantText(delta)]
        case "conversation.item.input_audio_transcription.completed",
             "conversation.item.input_audio_transcription.updated":
            // Both carry the whole utterance (xAI's "updated" is cumulative).
            guard let transcript = json["transcript"] as? String, !transcript.isEmpty else { return [] }
            return [.userText(transcript, replacing: true)]
        case "input_audio_buffer.speech_started":
            return [.userSpeechStarted]
        case "response.created":
            return [.responseStarted]
        case "error":
            let error = json["error"] as? [String: Any]
            return [.error(error?["message"] as? String ?? "The provider reported an error.", code: error?["code"] as? String)]
        default:
            return []
        }
    }
}

// MARK: - xAI Grok Voice

/// xAI's Grok Voice Agent API, which follows the Realtime event shape. It takes
/// function tools like OpenAI but no images.
nonisolated struct XAIVoiceWire: LiveWire {
    private let events = RealtimeLiveEvents()
    let inputSampleRate = 24_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = true

    func request(for config: LiveSessionConfig) -> URLRequest {
        var components = URLComponents(string: "wss://api.x.ai/v1/realtime")!
        components.queryItems = [URLQueryItem(name: "model", value: config.model)]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        var session: [String: Any] = [
            "voice": config.voice,
            "instructions": config.instructions,
            "turn_detection": ["type": "server_vad"],
            "audio": [
                "input": ["format": ["type": "audio/pcm", "rate": 24_000]],
                "output": ["format": ["type": "audio/pcm", "rate": 24_000]],
            ],
        ]
        if !config.tools.isEmpty {
            session["tools"] = config.tools.map(OpenAIRealtimeWire.functionTool)
        }
        var messages = [LiveJSON.string(["type": "session.update", "session": session])]
        // xAI's reference does not list tool_choice ("auto" is its behavior anyway), so it
        // goes in its own update: if xAI rejects it, the voice and tools still apply.
        if !config.tools.isEmpty {
            messages.append(LiveJSON.string(["type": "session.update", "session": ["tool_choice": "auto"]]))
        }
        return messages
    }

    func audioMessage(_ pcm16: Data) -> String {
        LiveJSON.string(["type": "input_audio_buffer.append", "audio": pcm16.base64EncodedString()])
    }

    /// xAI's voice API takes no image input.
    func imageMessages(_ jpeg: Data) -> [String] {
        []
    }

    func contextTextMessages(_ text: String) -> [String] {
        OpenAIRealtimeWire.userTextItem(text)
    }

    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] {
        OpenAIRealtimeWire.functionCallOutputs(responses)
    }

    func responseCreateMessage() -> String? {
        LiveJSON.string(["type": "response.create"])
    }

    func truncateMessage(itemID: String, audioEndMs: Int) -> String? {
        nil
    }

    func parse(_ message: String) -> [LiveEvent] {
        events.parse(message)
    }
}

// MARK: - Qwen Omni Realtime

/// Alibaba Model Studio's Qwen Omni Realtime, an OpenAI Realtime dialect: the same
/// audio, function-call and response events, 16 kHz input, and camera frames
/// through `input_image_buffer.append`.
nonisolated struct QwenRealtimeWire: LiveWire {
    private let events = RealtimeLiveEvents()
    private let audioGate = QwenAudioGate()
    let inputSampleRate = 16_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = true

    /// Qwen refuses camera frames larger than this.
    static let maxImageBytes = 256 * 1024
    /// Transcribes what the user says so captions and Coach get the user's words.
    /// Model Studio fixes this model for Qwen Omni Realtime.
    static let transcriptionModel = "qwen3-asr-flash-realtime"

    static func isValidWorkspace(_ value: String) -> Bool {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
            .wholeMatch(of: /[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?/) != nil
    }

    func request(for config: LiveSessionConfig) -> URLRequest {
        let workspace = config.qwenWorkspace.trimmingCharacters(in: .whitespacesAndNewlines)
        // Routing rejects an incomplete setup. Never attach a credential to an
        // unvalidated endpoint even if an internal caller bypasses routing.
        guard Self.isValidWorkspace(workspace) else {
            return URLRequest(url: URL(string: "about:blank")!)
        }
        var components = URLComponents(string: "wss://\(workspace).ap-southeast-1.maas.aliyuncs.com/api-ws/v1/realtime")!
        components.queryItems = [URLQueryItem(name: "model", value: config.model)]
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        let session: [String: Any] = [
            "modalities": ["text", "audio"],
            "audio": ["output": ["voice": config.voice]],
            "instructions": config.instructions,
            "input_audio_format": "pcm",
            "output_audio_format": "pcm",
            "turn_detection": ["type": "semantic_vad"],
        ]
        // Tools go in their own update, so a problem with them can't reject the
        // voice and turn-taking settings.
        var messages = [LiveJSON.string(["type": "session.update", "session": session])]
        if !config.tools.isEmpty {
            messages.append(LiveJSON.string(["type": "session.update", "session": [
                "tools": config.tools.map(OpenAIRealtimeWire.functionTool),
            ]]))
        }
        // Transcription goes alone too, so a problem with it can't drop the tools.
        messages.append(LiveJSON.string(["type": "session.update", "session": [
            "input_audio_transcription": ["model": Self.transcriptionModel],
        ]]))
        return messages
    }

    func audioMessage(_ pcm16: Data) -> String {
        audioGate.audioSent()
        return LiveJSON.string(["type": "input_audio_buffer.append", "audio": pcm16.base64EncodedString()])
    }

    /// Qwen takes images only after some audio and refuses frames over 256 KB;
    /// both are skipped rather than sent.
    func imageMessages(_ jpeg: Data) -> [String] {
        guard audioGate.hasSentAudio, jpeg.count <= Self.maxImageBytes else { return [] }
        return [LiveJSON.string(["type": "input_image_buffer.append", "image": jpeg.base64EncodedString()])]
    }

    func contextTextMessages(_ text: String) -> [String] {
        OpenAIRealtimeWire.userTextItem(text)
    }

    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] {
        OpenAIRealtimeWire.functionCallOutputs(responses)
    }

    func responseCreateMessage() -> String? {
        LiveJSON.string(["type": "response.create"])
    }

    func truncateMessage(itemID: String, audioEndMs: Int) -> String? {
        nil
    }

    /// Audio replies carry their words as audio transcripts; reading only those keeps
    /// captions from doubling if text deltas arrive too.
    func parse(_ message: String) -> [LiveEvent] {
        let type = LiveJSON.object(message)?["type"] as? String
        if type == "response.text.delta" || type == "response.output_text.delta" { return [] }
        return events.parse(message)
    }
}

/// Whether a Qwen session has sent microphone audio yet; images must come after it.
nonisolated final class QwenAudioGate: @unchecked Sendable {
    private let lock = NSLock()
    private var sent = false

    func audioSent() {
        lock.withLock { sent = true }
    }

    var hasSentAudio: Bool {
        lock.withLock { sent }
    }
}

// MARK: - OpenAI GPT-Live

/// OpenAI's GPT-Live: a full-duplex voice model that hands tool use to a backend
/// Responses model ("Responses delegation"). Its own events are `session.*`; the
/// backend's arrive wrapped in `response.event` and follow the Responses API.
/// It takes no images.
nonisolated struct GPTLiveWire: LiveWire {
    private let events = RealtimeLiveEvents()
    private let turns = GPTLiveTurns()
    let inputSampleRate = 24_000.0
    let outputSampleRate = 24_000.0
    let waitsForSetupAcknowledgement = true
    let needsResponseCreate = true

    /// The Responses model GPT-Live delegates the assistant's tools to.
    static let backendModel = "gpt-6-luna"

    func request(for config: LiveSessionConfig) -> URLRequest {
        var request = URLRequest(url: URL(string: "wss://api.openai.com/v1/live/sessions")!)
        request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        return request
    }

    func setupMessages(for config: LiveSessionConfig, resumptionHandle: String?) -> [String] {
        var backend: [String: Any] = ["model": Self.backendModel, "instructions": config.instructions]
        if !config.tools.isEmpty {
            backend["tools"] = config.tools.map(OpenAIRealtimeWire.functionTool)
            backend["tool_choice"] = "auto"
        }
        let session: [String: Any] = [
            "model": config.model,
            "instructions": config.instructions,
            "audio": ["output": ["voice": config.voice]],
            "delegation": ["type": "responses", "responses": backend],
        ]
        return [LiveJSON.string(["type": "session.start", "session": session])]
    }

    func audioMessage(_ pcm16: Data) -> String {
        LiveJSON.string(["type": "session.input_audio.append", "audio": pcm16.base64EncodedString()])
    }

    func imageMessages(_ jpeg: Data) -> [String] {
        []
    }

    /// Card taps go to the backend as a user message; `response.create` follows.
    func contextTextMessages(_ text: String) -> [String] {
        let content: [[String: Any]] = [["type": "input_text", "text": text]]
        return [LiveJSON.string(["type": "response.item.create", "item": ["type": "message", "role": "user", "content": content]])]
    }

    func toolResponseMessages(_ responses: [LiveToolResponse]) -> [String] {
        responses.map { response in
            LiveJSON.string(["type": "response.item.create", "item": [
                "type": "function_call_output",
                "call_id": response.id,
                "output": response.outputJSON,
            ]])
        }
    }

    func responseCreateMessage() -> String? {
        LiveJSON.string(["type": "response.create"])
    }

    func truncateMessage(itemID: String, audioEndMs: Int) -> String? {
        nil
    }

    func parse(_ message: String) -> [LiveEvent] {
        guard let json = LiveJSON.object(message), let type = json["type"] as? String else { return [] }
        switch type {
        case "session.started":
            return [.ready]
        case "session.output_audio.delta":
            guard let base64 = json["delta"] as? String, let audio = Data(base64Encoded: base64) else { return [] }
            turns.assistantSpoke()
            return [.audio(audio, itemID: nil)]
        case "session.output_transcript.delta":
            guard let delta = json["delta"] as? String, !delta.isEmpty else { return [] }
            turns.assistantSpoke()
            return [.assistantText(delta)]
        case "session.input_transcript.delta":
            guard let delta = json["delta"] as? String, !delta.isEmpty else { return [] }
            // GPT-Live reports no turn ends, so the user speaking after the
            // assistant closes the assistant's turn: the turn is saved now, its
            // playback stops, and the new words start a fresh caption.
            let ended: [LiveEvent] = turns.userSpoke() ? [.turnComplete, .userSpeechStarted] : []
            return ended + [.userText(delta, replacing: false)]
        case "response.event":
            guard let nested = json["event"] as? [String: Any] else { return [] }
            return backendEvents(nested)
        case "error":
            let error = json["error"] as? [String: Any]
            return [.error(error?["message"] as? String ?? "The provider reported an error.", code: error?["code"] as? String)]
        default:
            return []
        }
    }

    /// The backend's Responses lifecycle drives tool calls and `response.create`.
    /// Its text is spoken by GPT-Live and arrives again as the output transcript,
    /// so it isn't shown twice, and its completion never ends a spoken turn.
    private func backendEvents(_ nested: [String: Any]) -> [LiveEvent] {
        guard let type = nested["type"] as? String else { return [] }
        switch type {
        case "response.created", "response.output_item.added", "response.output_item.done":
            return events.parse(LiveJSON.string(nested))
        case "response.completed", "response.failed", "response.incomplete", "response.cancelled":
            var response = nested["response"] as? [String: Any] ?? [:]
            if response["status"] == nil {
                response["status"] = type == "response.completed" ? "completed" : String(type.dropFirst("response.".count))
            }
            return events.parse(LiveJSON.string(["type": "response.done", "response": response])).map { event in
                if case .responseDone(_, let successful) = event { return .responseDone(hasToolCalls: true, successful: successful) }
                return event
            }
        default:
            return []
        }
    }
}

/// Who spoke last in a GPT-Live session, to find where turns end.
nonisolated final class GPTLiveTurns: @unchecked Sendable {
    private let lock = NSLock()
    private var assistantSpokeLast = false

    func assistantSpoke() {
        lock.withLock { assistantSpokeLast = true }
    }

    /// True when the user starts speaking after the assistant did.
    func userSpoke() -> Bool {
        lock.withLock {
            defer { assistantSpokeLast = false }
            return assistantSpokeLast
        }
    }
}

// MARK: - JSON helpers

nonisolated enum LiveJSON {
    static func string(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    static func object(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}
