# AI provider refresh: review and rollout

No production configuration changes are needed to review this proposal. The
local-model lineup and offline OCR are unchanged. Provider keys, base URLs,
entitlements and fallback choices are preserved except where a removed preset
has an explicit same-provider replacement. Existing working choices and moving
router aliases are not forced onto a new flagship.

| Route | Proposal |
| --- | --- |
| BYOK public defaults | Use Kimi K3 for Fireworks photos, Qwen3.5 9B for Together text; remove the unavailable public-tier presets. Existing dedicated and enterprise endpoints can still use their literal model IDs through Custom (OpenAI-compatible); the public picker does not probe a key's tier. |
| Photo and text | Add DeepSeek Flash and Cerebras Qwen 3.8 vision. Cerebras is capped at two photos and a 10 MiB encoded request to work on its most restrictive public tier. Keep non-vision models in text only. |
| Gemini, Claude, xAI, Mistral | Add Hydrogen, optional Neon/Argon/Carbon, Haiku/Sonnet/Opus 5.5, Grok 4.7, Mistral Large 4 and Ministral 8B. Coach preserves full Gemini function parts and Mistral reasoning blocks during tool exchanges, but shows only the model's answer. |
| OpenAI | Migrate deprecated 4.1/4o Mini/Nano presets to Luna. Keep GPT-5.4 Mini as the default. Optional 6.1 Terra and 6.2 Sol/Astra use Chat Completions for analysis and stateless Responses for Coach's tools. |
| Other opt-in models | Fireworks MiniMax M3.1 Flash / Kimi K3.1 Pro, Together Kimi K3.1 Pro / MiniMax M3.1 Flash (text), Qwen4-Max, OpenRouter DeepSeek V4.1 Flash, and small pinned-size Ollama tags. Router availability is not guaranteed by the underlying model's vendor. |
| Recorded speech | Mistral Voxtral Mini Transcribe 3 and AssemblyAI STT 3.7 are the new defaults (AssemblyAI retains Universal-2). Gemini uses verbatim with `store:false`; OpenAI sends `languages[]`; Deepgram Automatic explicitly detects language. Optional Voxtral Small and Groq Whisper Turbo keep the same account and recorded flow. |
| iOS local speech | Apple SpeechAnalyzer is an optional recorded provider on supported iOS 26+ devices. Pick a language and download its Apple-managed asset in Settings before using it. Native live partials are distinct and not guaranteed offline. |
| Live Voice | Qwen needs a separate Singapore workspace/key. GPT-Live 1.1 and Gemini 3.8 Live Extended Thinking are opt-in; no change to Hosted or existing defaults. |
| Hosted | Keep generation `gemini-3.5-flash-lite`, Live `gemini-3.8-live`, and recorded `nova-2` by default. Compare `nova-3` on staging via the Worker-only override before changing the default. No second billed transcription request. |

## Streaming dictation experiment

Streaming speech-to-text is not Live Voice. The Gemini, OpenAI, Mistral and xAI
adapters here are developer experiments, not mobile picker options. They cover
setup acknowledgment, audio framing, application-controlled completion,
partial/final text handling, and a bounded synthetic-fixture smoke test.

```sh
node --test scripts/test-streaming-stt-wire.mjs
```

The live smoke test takes one provider at a time, requires `ffmpeg` and the
appropriate key in the environment, and can incur usage charges. Run it only
with authorization. It emits timing and a fixture-match flag, not the key or
transcript.

```sh
node scripts/run-streaming-stt-smoke.mjs openai --allow-live
```

Accepted providers: `gemini`, `openai`, `mistral`, `xai`. See the official
[Gemini Live Transcribe](https://aistudio.google.com/docs/live-api/live-transcribe),
[OpenAI Realtime Transcription](https://developers.openai.com/api/docs/guides/realtime-transcription),
[Mistral Realtime Transcribe](https://docs.mistral.ai/studio/audio/speech-to-text/transcribe-in-realtime),
and [xAI speech-to-text](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text)
docs. Connecting these codecs to mobile recording, testing interruption and
network drop, measuring accuracy/latency and checking provider costs are
acceptance gates before exposing streaming to users. xAI standalone STT is not
a replacement for Grok Live Voice.

Fable 5.2 and Grok 4.20 non-reasoning remain comparison candidates rather
than more default presets. Together's conflicting vision listings are not
enough to add photo routes for MiniMax M3.1 Flash, Qwen 3.8 Flash or GLM 5.3 Flash.

## Before merging or deploying

1. Verify both apps' saved primary and fallback choices and user-entered router IDs.
2. With approved synthetic inputs, check photos, labels, structured food JSON,
   multiple Coach tool rounds, fixed/automatic dictation and Live Voice
   interruption/camera/reconnect. Documentation and fixture tests don't prove
   a particular account or region is enabled.
3. Stage the Worker model override; check WAV and m4a, Hindi and code switching,
   latency, one-charge behavior and failures before changing any production pin.
