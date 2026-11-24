/**
 * Opt-in streaming dictation experiment. Protocol only: no microphone, credentials,
 * network, cloud deployment, or production app routing in this module.
 */
const geminiSocket = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

export function streamingDictationWire(provider, language) {
  if (language && !/^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})?$/.test(language)) throw new Error("invalid_language");
  switch (provider) {
    case "gemini":
      return {
        endpoint: geminiSocket,
        auth: "x-goog-api-key",
        sampleRate: 16_000,
        setup: {
          setup: {
            model: "models/gemini-3.5-transcribe-live",
            generationConfig: { responseModalities: ["TEXT"] },
            inputAudioTranscription: { mode: "VERBATIM", languageCodes: language ? [language] : [] },
            realtimeInputConfig: { automaticActivityDetection: { disabled: true } },
          },
        },
        ready: (event) => event.setupComplete !== undefined,
        begin: { realtimeInput: { activityStart: {} } },
        append: (data) => ({ realtimeInput: { audio: { mimeType: "audio/pcm;rate=16000", data } } }),
        end: { realtimeInput: { activityEnd: {} } },
        events: (event) => {
          const content = event.serverContent ?? {};
          return [
            typeof content.interimInputTranscription?.text === "string"
              && { type: "partial", mode: "replace", text: content.interimInputTranscription.text },
            typeof content.inputTranscription?.text === "string"
              && { type: "segment", text: content.inputTranscription.text },
            content.turnComplete === true && { type: "finished" },
          ].filter(Boolean);
        },
      };
    case "openai":
      return {
        endpoint: "wss://api.openai.com/v1/realtime?intent=transcription",
        auth: "Authorization",
        sampleRate: 24_000,
        setup: {
          type: "session.update",
          session: {
            type: "transcription",
            audio: {
              input: {
                format: { type: "audio/pcm", rate: 24_000 },
                transcription: { model: "gpt-live-transcribe", ...(language ? { languages: [language] } : {}) },
                turn_detection: null,
              },
            },
          },
        },
        ready: (event) => event.type === "session.updated",
        append: (audio) => ({ type: "input_audio_buffer.append", audio }),
        end: { type: "input_audio_buffer.commit" },
        events: (event) => {
          if (event.type === "conversation.item.input_audio_transcription.delta" && typeof event.delta === "string") {
            return [{ type: "partial", mode: "append", text: event.delta, item: event.item_id }];
          }
          if (event.type === "conversation.item.input_audio_transcription.completed" && typeof event.transcript === "string") {
            return [{ type: "segment", text: event.transcript, item: event.item_id }, { type: "finished" }];
          }
          return [];
        },
      };
    case "mistral":
      return {
        endpoint: "wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=voxtral-mini-transcribe-realtime-3",
        auth: "Authorization",
        sampleRate: 16_000,
        waitForCreated: true,
        setup: {
          type: "session.update",
          session: {
            audio_format: { encoding: "pcm_s16le", sample_rate: 16_000 },
            target_streaming_delay_ms: 480,
            ...(language ? { language: language.split("-")[0] } : {}),
          },
        },
        ready: (event) => event.type === "session.updated",
        append: (audio) => ({ type: "input_audio.append", audio }),
        end: { type: "input_audio.end" },
        events: (event) => {
          if (event.type === "transcription.text.delta" && typeof event.text === "string") {
            return [{ type: "partial", mode: "append", text: event.text }];
          }
          if (event.type === "transcription.done" && typeof event.text === "string") {
            return [{ type: "segment", text: event.text }, { type: "finished" }];
          }
          return [];
        },
      };
    case "xai": {
      const params = new URLSearchParams({
        model: "grok-voice-transcribe-2.0", sample_rate: "16000", encoding: "pcm", interim_results: "true",
      });
      if (language) params.set("language", language);
      return {
        endpoint: `wss://api.x.ai/v1/stt?${params}`,
        auth: "Authorization",
        sampleRate: 16_000,
        binaryAudio: true,
        ready: (event) => event.type === "transcript.created",
        end: { type: "audio.done" },
        events: (event) => {
          if (event.type === "transcript.partial" && typeof event.text === "string") {
            return [{ type: "partial", mode: "replace", text: event.text }];
          }
          if (event.type === "transcript.done" && typeof event.text === "string") {
            return [{ type: "segment", text: event.text }, { type: "finished" }];
          }
          return [];
        },
      };
    }
    default: throw new Error("unsupported_provider");
  }
}
