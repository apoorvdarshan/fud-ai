// Explicit manual smoke test: opening a provider session may incur usage charges.
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import { streamingDictationWire } from "./streaming-stt-wire.mjs";

const [provider, approval, ...extra] = process.argv.slice(2);
const keyVariables = {
  gemini: "GEMINI_API_KEY",
  openai: "OPENAI_API_KEY",
  mistral: "MISTRAL_API_KEY",
  xai: "XAI_API_KEY",
};

async function smoke() {
  if (!Object.hasOwn(keyVariables, provider) || approval !== "--allow-live" || extra.length) {
    throw new Error("Usage: node scripts/run-streaming-stt-smoke.mjs gemini|openai|mistral|xai --allow-live");
  }
  const key = process.env[keyVariables[provider]]?.trim();
  if (!key) throw new Error(`${keyVariables[provider]} is required`);
  const wire = streamingDictationWire(provider, "en");
  const fixture = fileURLToPath(new URL("../web/tests/fixtures/speech.m4a", import.meta.url));
  const pcm = execFileSync("ffmpeg", [
    "-v", "error", "-i", fixture, "-t", "5", "-ac", "1", "-ar", String(wire.sampleRate), "-f", "s16le", "-",
  ], { timeout: 5_000, maxBuffer: 512 * 1024, env: { PATH: process.env.PATH } });
  if (!pcm.length) throw new Error("empty_fixture");

  const start = performance.now();
  const socket = new WebSocket(wire.endpoint, {
    headers: { [wire.auth]: wire.auth === "Authorization" ? `Bearer ${key}` : key },
  });
  socket.binaryType = "arraybuffer";
  return new Promise((resolve, reject) => {
    let streaming = false;
    let audioEnded = false;
    let settled = false;
    let firstAudio;
    let firstPartial;
    let firstFinal;
    let segments = 0;
    let transcript = "";
    const timeout = setTimeout(() => finish("provider_timeout"), 25_000);
    const send = (value) => socket.send(JSON.stringify(value));

    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      socket.close();
      if (error) reject(new Error(error));
      else resolve({
        provider,
        setupMs: Math.round(firstAudio - start),
        firstPartialMs: firstPartial === undefined ? null : Math.round(firstPartial - firstAudio),
        firstFinalMs: firstFinal === undefined ? null : Math.round(firstFinal - firstAudio),
        segments,
        matchedFixture: /\b(two|2)\s+eggs\b/i.test(transcript),
      });
    }

    async function streamAudio() {
      if (wire.begin) send(wire.begin);
      firstAudio = performance.now();
      const chunkSize = wire.sampleRate * 2 / 10;
      for (let offset = 0; offset < pcm.length && !settled; offset += chunkSize) {
        const chunk = pcm.subarray(offset, offset + chunkSize);
        if (wire.binaryAudio) socket.send(chunk);
        else send(wire.append(chunk.toString("base64")));
        await delay(100);
      }
      if (settled) return;
      audioEnded = true;
      send(wire.end);
    }

    socket.addEventListener("open", () => { if (wire.setup && !wire.waitForCreated) send(wire.setup); });
    socket.addEventListener("error", () => finish("provider_socket_error"));
    socket.addEventListener("close", () => finish("provider_closed_before_final_transcript"));
    socket.addEventListener("message", ({ data }) => {
      try {
        const event = JSON.parse(typeof data === "string" ? data : Buffer.from(data).toString("utf8"));
        if (event.type === "error" || event.error) return finish("provider_rejected_request");
        if (wire.waitForCreated && event.type === "session.created") send(wire.setup);
        if (!streaming && wire.ready(event)) {
          streaming = true;
          streamAudio().catch(() => finish("audio_stream_failed"));
        }
        for (const decoded of wire.events(event)) {
          if (decoded.type === "partial") firstPartial ??= performance.now();
          if (decoded.type === "segment") {
            firstFinal ??= performance.now();
            segments++;
            transcript += ` ${decoded.text}`;
          }
          if (decoded.type === "finished" && audioEnded && segments) finish();
        }
      } catch {
        finish("invalid_provider_frame");
      }
    });
  });
}

try {
  const result = await smoke();
  console.log(JSON.stringify(result));
  if (!result.matchedFixture) process.exitCode = 1;
} catch (error) {
  // Only print pre-defined errors; undici or upstream messages can contain headers.
  const safe = /^(Usage: |[A-Z_]+ is required$|(?:empty_fixture|provider_\w+|audio_stream_failed|invalid_provider_frame)$)/;
  console.error(safe.test(error.message) ? error.message : "streaming_smoke_failed");
  process.exitCode = 1;
}
