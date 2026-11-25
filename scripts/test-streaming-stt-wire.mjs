import assert from "node:assert/strict";
import test from "node:test";
import { streamingDictationWire } from "./streaming-stt-wire.mjs";

test("Gemini setup uses verbatim with separately handled interim/final text", () => {
  const wire = streamingDictationWire("gemini");
  assert.equal(new URL(wire.endpoint).search, "");
  assert.equal(wire.auth, "x-goog-api-key");
  assert.deepEqual(wire.setup.setup.inputAudioTranscription, { mode: "VERBATIM", languageCodes: [] });
  assert(wire.ready({ setupComplete: {} }));
  assert.deepEqual(wire.end, { realtimeInput: { activityEnd: {} } });
  assert.deepEqual(wire.events({ serverContent: {
    interimInputTranscription: { text: "two" }, inputTranscription: { text: "two eggs" }, turnComplete: true,
  } }), [
    { type: "partial", mode: "replace", text: "two" }, { type: "segment", text: "two eggs" }, { type: "finished" },
  ]);
});

test("OpenAI transcribes a manually committed PCM24 turn with no server VAD", () => {
  const wire = streamingDictationWire("openai", "hi");
  assert.equal(new URL(wire.endpoint).searchParams.get("intent"), "transcription");
  assert.equal(wire.setup.session.audio.input.format.rate, 24_000);
  assert.equal(wire.setup.session.audio.input.turn_detection, null);
  assert.deepEqual(wire.setup.session.audio.input.transcription.languages, ["hi"]);
  assert.deepEqual(wire.end, { type: "input_audio_buffer.commit" });
  assert.deepEqual(wire.events({
    type: "conversation.item.input_audio_transcription.completed", item_id: "fixture", transcript: "two eggs",
  }), [{ type: "segment", item: "fixture", text: "two eggs" }, { type: "finished" }]);
});

test("Voxtral waits for the configured session before streaming and ends explicitly", () => {
  const wire = streamingDictationWire("mistral", "pt-BR");
  assert(wire.waitForCreated);
  assert(!wire.ready({ type: "session.created" }));
  assert(wire.ready({ type: "session.updated" }));
  assert.equal(wire.setup.session.language, "pt");
  assert.equal(wire.setup.session.audio_format.encoding, "pcm_s16le");
  assert.deepEqual(wire.append("AA=="), { type: "input_audio.append", audio: "AA==" });
  assert.deepEqual(wire.end, { type: "input_audio.end" });
  assert.deepEqual(wire.events({ type: "transcription.done", text: "two eggs" }), [
    { type: "segment", text: "two eggs" }, { type: "finished" },
  ]);
});

test("xAI STT is a binary audio route, not the Grok Voice conversation socket", () => {
  const wire = streamingDictationWire("xai", "en");
  assert.equal(new URL(wire.endpoint).pathname, "/v1/stt");
  assert.equal(new URL(wire.endpoint).searchParams.get("model"), "grok-voice-transcribe-2.0");
  assert(wire.binaryAudio);
  assert(wire.ready({ type: "transcript.created" }));
  assert.deepEqual(wire.end, { type: "audio.done" });
  assert.deepEqual(wire.events({ type: "transcript.done", text: "two eggs" }), [
    { type: "segment", text: "two eggs" }, { type: "finished" },
  ]);
});
