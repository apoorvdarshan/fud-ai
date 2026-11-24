package com.apoorvdarshan.calorietracker.services.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveWireTest {
    private val tools = VoiceToolCatalog.declared(RepoFiles.catalog, workoutDiaryEnabled = true)

    private fun config(provider: LiveProvider) = LiveSessionConfig(
        provider = provider,
        model = provider.defaultModel,
        voice = provider.defaultVoice,
        instructions = "Be brief.",
        tools = tools,
        apiKey = "test-key",
        hostedUserId = "\$RCAnonymousID:0123456789abcdef0123456789abcdef"
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // MARK: - Gemini and hosted

    @Test
    fun geminiRequestKeepsTheKeyInAHeader() {
        val request = GeminiLiveWire().request(config(LiveProvider.GEMINI))
        assertEquals("test-key", request.header("x-goog-api-key"))
        assertTrue(request.url.toString().startsWith("https://generativelanguage.googleapis.com/ws/"))
        assertNull(request.url.queryParameter("key"))
    }

    @Test
    fun geminiSetupDeclaresModelVoiceToolsAndTranscription() {
        val messages = GeminiLiveWire().setupMessages(config(LiveProvider.GEMINI), resumptionHandle = null)
        assertEquals(1, messages.size)
        val setup = json(messages[0])["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val generation = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", generation["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "Kore",
            generation["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!
                .jsonObject["voiceName"]!!.jsonPrimitive.content
        )
        assertEquals(
            "Be brief.",
            setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        )
        val declarations = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(tools.size, declarations.size)
        assertTrue(setup.containsKey("inputAudioTranscription"))
        assertTrue(setup.containsKey("outputAudioTranscription"))
        assertTrue(setup["contextWindowCompression"]!!.jsonObject.containsKey("slidingWindow"))
        assertTrue(setup["sessionResumption"]!!.jsonObject.isEmpty())
    }

    @Test
    fun geminiSetupResumesWithAHandle() {
        val setup = json(GeminiLiveWire().setupMessages(config(LiveProvider.GEMINI), "handle-1")[0])["setup"]!!.jsonObject
        assertEquals("handle-1", setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiEncodesAudioImagesTextAndToolResponses() {
        val wire = GeminiLiveWire()
        val audio = json(wire.audioMessage(byteArrayOf(1, 2, 3, 4)))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals(b64(byteArrayOf(1, 2, 3, 4)), audio["data"]!!.jsonPrimitive.content)

        val video = json(wire.imageMessages(byteArrayOf(9))[0])["realtimeInput"]!!.jsonObject["video"]!!.jsonObject
        assertEquals("image/jpeg", video["mimeType"]!!.jsonPrimitive.content)

        val text = json(wire.textMessages("[Fud AI app] Confirmed.", respond = true).single())["clientContent"]!!.jsonObject
        assertTrue(text["turnComplete"]!!.jsonPrimitive.boolean)
        val turn = text["turns"]!!.jsonArray[0].jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        assertEquals("[Fud AI app] Confirmed.", turn["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)

        val result = ToolResults.ok { }
        val response = json(wire.toolResultMessages(listOf(LiveToolResult("call-1", "log_water", result))).single())
        val functionResponse = response["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray[0].jsonObject
        assertEquals("call-1", functionResponse["id"]!!.jsonPrimitive.content)
        assertEquals("log_water", functionResponse["name"]!!.jsonPrimitive.content)
        assertTrue(functionResponse["response"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)

        assertNull(wire.responseCreateMessage())
        assertNull(wire.truncateMessage("item", 100))
        assertTrue(wire.toolResultMessages(emptyList()).isEmpty())
    }

    @Test
    fun geminiParsesAudioTranscriptsAndTurnState() {
        val pcm = byteArrayOf(5, 6, 7, 8)
        val events = GeminiLiveWire().parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${b64(pcm)}"}}]},
               "inputTranscription":{"text":"log two"},"outputTranscription":{"text":"Sure,"},"interrupted":true,"turnComplete":true}}"""
        )
        val audio = events.filterIsInstance<LiveEvent.Audio>().single()
        assertArrayEquals(pcm, audio.pcm)
        assertNull(audio.itemId)
        assertEquals(LiveEvent.UserText("log two", replacing = false), events.filterIsInstance<LiveEvent.UserText>().single())
        assertEquals(LiveEvent.AssistantText("Sure,"), events.filterIsInstance<LiveEvent.AssistantText>().single())
        assertTrue(LiveEvent.Interrupted in events)
        assertTrue(events.any { it is LiveEvent.TurnComplete })
        assertEquals(listOf(LiveEvent.Ready), GeminiLiveWire().parse("""{"setupComplete":{}}"""))
    }

    @Test
    fun geminiParsesToolCallsAndCancellations() {
        val events = GeminiLiveWire().parse(
            """{"toolCall":{"functionCalls":[{"id":"c1","name":"log_water","args":{"amount":500,"unit":"ml"}},
               {"id":"c2","name":"get_app_state"}]}}"""
        )
        val calls = (events.single() as LiveEvent.ToolCalls).calls
        assertEquals(listOf("c1", "c2"), calls.map { it.id })
        assertEquals("ml", calls[0].arguments["unit"]!!.jsonPrimitive.content)
        assertTrue(calls[1].arguments.isEmpty())

        assertEquals(
            listOf(LiveEvent.ToolCallsCancelled(listOf("c1"))),
            GeminiLiveWire().parse("""{"toolCallCancellation":{"ids":["c1"]}}""")
        )
    }

    @Test
    fun geminiParsesResumptionAndGoAway() {
        assertEquals(
            listOf(LiveEvent.ResumptionHandle("h2")),
            GeminiLiveWire().parse("""{"sessionResumptionUpdate":{"newHandle":"h2","resumable":true}}""")
        )
        assertTrue(GeminiLiveWire().parse("""{"sessionResumptionUpdate":{"newHandle":"h3","resumable":false}}""").isEmpty())
        assertEquals(listOf(LiveEvent.GoAway), GeminiLiveWire().parse("""{"goAway":{"timeLeft":"10s"}}"""))
        assertTrue(GeminiLiveWire().parse("not json").isEmpty())
    }

    @Test
    fun hostedConnectsToTheWorkerWithIdentityHeaders() {
        val request = HostedLiveWire().request(config(LiveProvider.HOSTED))
        assertEquals("https://fud-ai.app/api/hosted-ai/v1/live", request.url.toString())
        assertEquals("\$RCAnonymousID:0123456789abcdef0123456789abcdef", request.header("X-Fud-User-Id"))
        assertEquals("android", request.header("X-Fud-Platform"))
        assertNull(request.header("x-goog-api-key"))
    }

    @Test
    fun hostedSetupIsTheGeminiSetupFirst() {
        val messages = HostedLiveWire().setupMessages(config(LiveProvider.HOSTED), null)
        val setup = json(messages.single())["setup"]!!.jsonObject
        assertEquals(
            setOf(
                "model", "generationConfig", "systemInstruction", "tools", "inputAudioTranscription",
                "outputAudioTranscription", "contextWindowCompression", "sessionResumption"
            ),
            setup.keys
        )
    }

    @Test
    fun hostedParsesFudVoiceNotices() {
        val wire = HostedLiveWire()
        assertEquals(listOf(LiveEvent.HostedStatus(840)), wire.parse("""{"fudVoice":{"secondsRemaining":840}}"""))
        assertEquals(
            listOf(LiveEvent.HostedNotice("voice_quota_exceeded")),
            wire.parse("""{"fudVoice":{"error":"voice_quota_exceeded"}}""")
        )
        assertEquals(listOf(LiveEvent.Ready), wire.parse("""{"setupComplete":{}}"""))
    }

    // MARK: - OpenAI

    @Test
    fun openAIRequestCarriesTheModelAndBearerKey() {
        val request = OpenAIRealtimeWire().request(config(LiveProvider.OPENAI))
        assertEquals("api.openai.com", request.url.host)
        assertEquals("gpt-realtime-2.1-mini", request.url.queryParameter("model"))
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertNull(request.header("OpenAI-Beta"))
    }

    @Test
    fun openAISessionUpdateDeclaresToolsVoiceAndTurnDetection() {
        val messages = OpenAIRealtimeWire().setupMessages(config(LiveProvider.OPENAI), null)
        assertEquals(2, messages.size)
        val first = json(messages[0])
        assertEquals("session.update", first["type"]!!.jsonPrimitive.content)
        val session = first["session"]!!.jsonObject
        assertEquals("realtime", session["type"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", session["instructions"]!!.jsonPrimitive.content)
        assertEquals("auto", session["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(tools.size, session["tools"]!!.jsonArray.size)
        val audio = session["audio"]!!.jsonObject
        assertEquals(24_000, audio["input"]!!.jsonObject["format"]!!.jsonObject["rate"]!!.jsonPrimitive.int)
        assertEquals("semantic_vad", audio["input"]!!.jsonObject["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("marin", audio["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        val transcription = json(messages[1])["session"]!!.jsonObject["audio"]!!.jsonObject["input"]!!.jsonObject
        assertTrue(transcription.containsKey("transcription"))
    }

    @Test
    fun openAIEncodesTextImagesToolOutputsAndTruncation() {
        val wire = OpenAIRealtimeWire()
        val text = wire.textMessages("[Fud AI app] Cancelled.", respond = true)
        assertEquals(2, text.size)
        val item = json(text[0])["item"]!!.jsonObject
        assertEquals("message", item["type"]!!.jsonPrimitive.content)
        assertEquals("input_text", item["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("response.create", json(text[1])["type"]!!.jsonPrimitive.content)
        assertEquals(1, wire.textMessages("quiet", respond = false).size)

        val image = json(wire.imageMessages(byteArrayOf(1)).single())["item"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_image", image["type"]!!.jsonPrimitive.content)
        assertTrue(image["image_url"]!!.jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))

        val output = json(wire.toolResultMessages(listOf(LiveToolResult("call_9", "log_water", ToolResults.ok { }))).single())
        val outputItem = output["item"]!!.jsonObject
        assertEquals("function_call_output", outputItem["type"]!!.jsonPrimitive.content)
        assertEquals("call_9", outputItem["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"ok":true}""", outputItem["output"]!!.jsonPrimitive.content)
        assertEquals("response.create", json(wire.responseCreateMessage())["type"]!!.jsonPrimitive.content)

        val truncate = json(wire.truncateMessage("item_1", 1_250)!!)
        assertEquals("conversation.item.truncate", truncate["type"]!!.jsonPrimitive.content)
        assertEquals(0, truncate["content_index"]!!.jsonPrimitive.int)
        assertEquals(1_250, truncate["audio_end_ms"]!!.jsonPrimitive.int)
    }

    @Test
    fun openAIParsesAudioTranscriptsAndSpeech() {
        val wire = OpenAIRealtimeWire()
        val audio = wire.parse("""{"type":"response.output_audio.delta","item_id":"item_1","delta":"${b64(byteArrayOf(1, 2))}"}""")
            .single() as LiveEvent.Audio
        assertEquals("item_1", audio.itemId)
        assertEquals(
            listOf(LiveEvent.AssistantText("Logged.")),
            wire.parse("""{"type":"response.output_audio_transcript.delta","delta":"Logged."}""")
        )
        assertEquals(
            listOf(LiveEvent.UserText("two eggs", replacing = true)),
            wire.parse("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"two eggs"}""")
        )
        assertEquals(listOf(LiveEvent.UserSpeechStarted), wire.parse("""{"type":"input_audio_buffer.speech_started"}"""))
        assertEquals(
            listOf(LiveEvent.ProviderError("Bad key")),
            wire.parse("""{"type":"error","error":{"message":"Bad key"}}""")
        )
    }

    @Test
    fun openAIReleasesToolCallsWhenTheResponseCompletes() {
        val wire = OpenAIRealtimeWire()
        assertTrue(
            wire.parse("""{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","name":"log_water"}}""").isEmpty()
        )
        // The arguments event omits the name here; it comes from output_item.added.
        assertTrue(
            wire.parse(
                """{"type":"response.function_call_arguments.done","response_id":"resp_1","item_id":"fc_1","call_id":"call_1","arguments":"{\"amount\":2,\"unit\":\"cup\"}"}"""
            ).isEmpty()
        )
        // The same call again from output_item.done is not duplicated.
        wire.parse(
            """{"type":"response.output_item.done","response_id":"resp_1","item":{"type":"function_call","status":"completed","call_id":"call_1","name":"log_water","arguments":"{}"}}"""
        )
        val events = wire.parse("""{"type":"response.done","response":{"id":"resp_1","status":"completed","output":[]}}""")
        val calls = (events[0] as LiveEvent.ToolCalls).calls
        assertEquals(1, calls.size)
        assertEquals("log_water", calls[0].name)
        assertEquals("call_1", calls[0].id)
        assertEquals("cup", calls[0].arguments["unit"]!!.jsonPrimitive.content)
        assertEquals(LiveEvent.TurnComplete("resp_1"), events[1])
    }

    @Test
    fun openAIDropsCallsFromAnInterruptedResponse() {
        val wire = OpenAIRealtimeWire()
        wire.parse(
            """{"type":"response.function_call_arguments.done","response_id":"resp_2","item_id":"fc_2","call_id":"call_2","name":"delete_food_entry","arguments":"{}"}"""
        )
        val events = wire.parse("""{"type":"response.done","response":{"id":"resp_2","status":"cancelled"}}""")
        assertEquals(listOf(LiveEvent.TurnComplete("resp_2", successful = false)), events)
    }

    @Test
    fun realtimeCompletionPreservesUnsuccessfulStatusWithoutFailingBargeIn() {
        for (wire in listOf(OpenAIRealtimeWire(), XAIVoiceWire())) {
            for (status in listOf("cancelled", "incomplete")) {
                val events = wire.parse("""{"type":"response.done","response":{"id":"r1","status":"$status"}}""")
                // A normal conversation can continue after cancellation: no ProviderError.
                assertEquals(listOf(LiveEvent.TurnComplete("r1", successful = false)), events)
            }
            for (status in listOf("\"status\":\"completed\",", "")) {
                val events = wire.parse("""{"type":"response.done","response":{$status"id":"r1"}}""")
                assertEquals(listOf(LiveEvent.TurnComplete("r1")), events)
            }
        }
    }

    @Test
    fun openAIReadsCallsListedOnlyInResponseDone() {
        val events = OpenAIRealtimeWire().parse(
            """{"type":"response.done","response":{"id":"resp_3","status":"completed","output":[
               {"type":"function_call","call_id":"call_3","name":"get_app_state","arguments":"{}"}]}}"""
        )
        assertEquals("get_app_state", (events[0] as LiveEvent.ToolCalls).calls.single().name)
    }

    // MARK: - xAI

    @Test
    fun xaiSessionUpdateIsFlatWithToolsAndNoToolChoice() {
        val wire = XAIVoiceWire()
        val request = wire.request(config(LiveProvider.XAI))
        assertEquals("api.x.ai", request.url.host)
        assertEquals("grok-voice-latest", request.url.queryParameter("model"))
        assertEquals("Bearer test-key", request.header("Authorization"))

        val session = json(wire.setupMessages(config(LiveProvider.XAI), null).single())["session"]!!.jsonObject
        assertEquals("ara", session["voice"]!!.jsonPrimitive.content)
        assertEquals("server_vad", session["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(tools.size, session["tools"]!!.jsonArray.size)
        assertFalse(session.containsKey("tool_choice"))
        assertFalse(session.containsKey("type"))
    }

    @Test
    fun xaiTakesNoImagesAndReadsCallsFromItsEvents() {
        val wire = XAIVoiceWire()
        assertTrue(wire.imageMessages(byteArrayOf(1)).isEmpty())
        wire.parse(
            """{"type":"response.function_call_arguments.done","response_id":"r1","item_id":"i1","call_id":"x1","name":"log_cardio","arguments":"{\"activity\":\"running\",\"minutes\":20}"}"""
        )
        // xAI's response.done carries no output list.
        val events = wire.parse("""{"type":"response.done","response":{"id":"r1"}}""")
        val call = (events[0] as LiveEvent.ToolCalls).calls.single()
        assertEquals("log_cardio", call.name)
        assertEquals("20", call.arguments["minutes"]!!.jsonPrimitive.content)
        // Only one of xAI's two text-delta names is read, so captions don't double.
        assertTrue(wire.parse("""{"type":"response.text.delta","delta":"Hi"}""").isEmpty())
        assertEquals(
            listOf(LiveEvent.ProviderError("flat error")),
            wire.parse("""{"type":"error","error":"flat error"}""")
        )
    }

    // MARK: - Qwen

    @Test
    fun qwenKeepsTheKeyInAHeaderAndSendsToolsSeparately() {
        val wire = QwenRealtimeWire()
        val request = wire.request(config(LiveProvider.QWEN).copy(qwenWorkspace = "llm-demo"))
        assertEquals("llm-demo.ap-southeast-1.maas.aliyuncs.com", request.url.host)
        assertEquals("/api-ws/v1/realtime", request.url.encodedPath)
        assertEquals("qwen3.8-omni-flash-realtime", request.url.queryParameter("model"))
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertFalse(request.url.toString().contains("test-key"))
        assertEquals(16_000, wire.inputSampleRate)
        assertEquals(24_000, wire.outputSampleRate)

        val messages = wire.setupMessages(config(LiveProvider.QWEN), null).map(::json)
        assertEquals(3, messages.size)
        val session = messages[0]["session"]!!.jsonObject
        assertEquals("Tina", session["audio"]!!.jsonObject["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", session["input_audio_format"]!!.jsonPrimitive.content)
        assertEquals(listOf("text", "audio"), session["modalities"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("semantic_vad", session["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(session.containsKey("tools"))
        assertEquals(tools.size, messages[1]["session"]!!.jsonObject["tools"]!!.jsonArray.size)
        assertEquals(
            "qwen3-asr-flash-realtime",
            messages[2]["session"]!!.jsonObject["input_audio_transcription"]!!.jsonObject["model"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun qwenRejectsUntrustedWorkspaceHosts() {
        listOf("", "example.com", "-bad", "host@attacker.com", "abc/def", "a".repeat(64)).forEach {
            assertFalse(QwenRealtimeWire.isValidWorkspace(it))
            assertTrue(runCatching {
                QwenRealtimeWire().request(config(LiveProvider.QWEN).copy(qwenWorkspace = it))
            }.isFailure)
        }
    }

    @Test
    fun onlyByokGeminiExtendedThinkingSendsThinkingAndNonBlockingTools() {
        val model = "gemini-3.8-live-extended-thinking"
        val wire = GeminiLiveWire()
        val setup = json(wire.setupMessages(config(LiveProvider.GEMINI).copy(model = model), null).single())
            .getValue("setup").jsonObject
        assertEquals("LOW", setup.getValue("generationConfig").jsonObject
            .getValue("thinkingConfig").jsonObject.getValue("thinkingLevel").jsonPrimitive.content)
        assertEquals("NON_BLOCKING", setup.getValue("tools").jsonArray.first().jsonObject
            .getValue("functionDeclarations").jsonArray.first().jsonObject.getValue("behavior").jsonPrimitive.content)
        val defaultSetup = json(wire.setupMessages(config(LiveProvider.GEMINI), null).single()).getValue("setup").jsonObject
        assertFalse(defaultSetup.getValue("generationConfig").jsonObject.containsKey("thinkingConfig"))
    }

    @Test
    fun qwenSeesTheCameraAndReadsCallsAndAudioTranscripts() {
        val wire = QwenRealtimeWire()
        // Qwen takes images only after some audio.
        assertTrue(wire.imageMessages(byteArrayOf(1, 2, 3)).isEmpty())
        wire.audioMessage(byteArrayOf(0, 1))
        val image = json(wire.imageMessages(byteArrayOf(1, 2, 3)).single())
        assertEquals("input_image_buffer.append", image["type"]!!.jsonPrimitive.content)
        assertEquals(b64(byteArrayOf(1, 2, 3)), image["image"]!!.jsonPrimitive.content)
        assertTrue(wire.imageMessages(ByteArray(QwenRealtimeWire.MAX_IMAGE_BYTES + 1)).isEmpty())
        assertNull(wire.truncateMessage("i1", 100))
        assertTrue(wire.toolOutputsNeedResponseCreate)

        wire.parse("""{"type":"response.created","response":{"id":"r1"}}""")
        wire.parse(
            """{"type":"response.function_call_arguments.done","response_id":"r1","item_id":"i1","call_id":"q1","name":"log_water","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}"""
        )
        val events = wire.parse("""{"type":"response.done","response":{"id":"r1","status":"completed"}}""")
        val call = (events[0] as LiveEvent.ToolCalls).calls.single()
        assertEquals("log_water", call.name)
        assertEquals("250", call.arguments["amount"]!!.jsonPrimitive.content)
        // Audio transcripts are the captions; text deltas would double them.
        assertTrue(wire.parse("""{"type":"response.text.delta","delta":"Hi"}""").isEmpty())
        assertEquals(
            listOf(LiveEvent.AssistantText("Hi")),
            wire.parse("""{"type":"response.audio_transcript.delta","delta":"Hi"}""")
        )
    }

    // MARK: - GPT-Live

    @Test
    fun gptLiveStartsWithResponsesDelegationAndTakesNoImages() {
        val wire = GPTLiveWire()
        val request = wire.request(config(LiveProvider.GPT_LIVE))
        assertEquals("https://api.openai.com/v1/live/sessions", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals(24_000, wire.inputSampleRate)

        val start = json(wire.setupMessages(config(LiveProvider.GPT_LIVE), null).single())
        assertEquals("session.start", start["type"]!!.jsonPrimitive.content)
        val session = start["session"]!!.jsonObject
        assertEquals("gpt-live-1", session["model"]!!.jsonPrimitive.content)
        assertEquals("quartz", session["audio"]!!.jsonObject["output"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        val delegation = session["delegation"]!!.jsonObject
        assertEquals("responses", delegation["type"]!!.jsonPrimitive.content)
        val backend = delegation["responses"]!!.jsonObject
        assertEquals(GPTLiveWire.BACKEND_MODEL, backend["model"]!!.jsonPrimitive.content)
        assertEquals(tools.size, backend["tools"]!!.jsonArray.size)

        assertEquals("session.input_audio.append", json(wire.audioMessage(byteArrayOf(0, 1)))["type"]!!.jsonPrimitive.content)
        assertTrue(wire.imageMessages(byteArrayOf(1)).isEmpty())
        val output = json(wire.toolResultMessages(listOf(LiveToolResult("call_1", "log_water", JsonObject(emptyMap())))).single())
        assertEquals("response.item.create", output["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", output["item"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals(2, wire.textMessages("Saved.", respond = true).size)
    }

    @Test
    fun gptLiveReadsBackendCallsAndEndsTurnsWhenTheUserSpeaks() {
        val wire = GPTLiveWire()
        assertEquals(listOf(LiveEvent.Ready), wire.parse("""{"type":"session.started","session":{"id":"s1"}}"""))
        assertEquals(
            listOf(LiveEvent.AssistantText("Logging it.")),
            wire.parse("""{"type":"session.output_transcript.delta","delta":"Logging it."}""")
        )
        assertEquals(
            listOf(
                LiveEvent.TurnComplete(endsResponse = false),
                LiveEvent.UserSpeechStarted,
                LiveEvent.UserText("thanks", replacing = false)
            ),
            wire.parse("""{"type":"session.input_transcript.delta","delta":"thanks"}""")
        )
        assertEquals(
            listOf(LiveEvent.UserText(" a lot", replacing = false)),
            wire.parse("""{"type":"session.input_transcript.delta","delta":" a lot"}""")
        )
        wire.parse("""{"type":"response.event","delegation_id":"d1","event":{"type":"response.created","response":{"id":"resp_1"}}}""")
        wire.parse(
            """{"type":"response.event","delegation_id":"d1","event":{"type":"response.output_item.done","item":{"type":"function_call","status":"completed","call_id":"call_9","name":"log_water","arguments":"{\"amount\":250,\"unit\":\"ml\"}"}}}"""
        )
        assertTrue(wire.parse("""{"type":"response.event","event":{"type":"response.output_text.delta","delta":"Done"}}""").isEmpty())
        val events = wire.parse("""{"type":"response.event","delegation_id":"d1","event":{"type":"response.completed","response":{"id":"resp_1"}}}""")
        val call = (events[0] as LiveEvent.ToolCalls).calls.single()
        assertEquals("log_water", call.name)
        assertEquals("250", call.arguments["amount"]!!.jsonPrimitive.content)
        // The backend finishing frees response.create but doesn't end the spoken turn.
        val done = events.last() as LiveEvent.TurnComplete
        assertTrue(done.endsResponse)
        assertFalse(done.endsSpokenTurn)
    }

    @Test
    fun wiresUseTheContractSampleRates() {
        assertEquals(16_000, GeminiLiveWire().inputSampleRate)
        assertEquals(16_000, HostedLiveWire().inputSampleRate)
        assertEquals(24_000, GeminiLiveWire().outputSampleRate)
        assertEquals(24_000, OpenAIRealtimeWire().inputSampleRate)
        assertEquals(24_000, XAIVoiceWire().outputSampleRate)
        assertTrue(OpenAIRealtimeWire().toolOutputsNeedResponseCreate)
        assertFalse(GeminiLiveWire().toolOutputsNeedResponseCreate)
        assertTrue(GeminiLiveWire().waitsForSetupAcknowledgement)
        assertTrue(XAIVoiceWire().waitsForSetupAcknowledgement)
        assertTrue(OpenAIRealtimeWire().waitsForSetupAcknowledgement)
    }

    @Test
    fun realtimeProvidersAcknowledgeTheSessionConfiguration() {
        listOf(OpenAIRealtimeWire(), XAIVoiceWire(), QwenRealtimeWire()).forEach { wire ->
            assertTrue(wire.parse("""{"type":"session.created","session":{}}""").isEmpty())
            assertEquals(listOf(LiveEvent.Ready), wire.parse("""{"type":"session.updated","session":{}}"""))
        }
    }

    @Test
    fun failedResponseReportsTheReasonWithoutRunningItsTools() {
        val events = OpenAIRealtimeWire().parse("""{
            "type":"response.done",
            "response":{"id":"r-failed","status":"failed",
              "status_details":{"error":{"message":"Insufficient quota"}},
              "output":[{"type":"function_call","call_id":"c1","name":"log_water","arguments":"{}"}]}
        }""")
        assertEquals(listOf(LiveEvent.ProviderError("Insufficient quota"), LiveEvent.TurnComplete("r-failed", successful = false)), events)
    }
}
