import { afterEach, describe, expect, it, vi } from "vitest";
import { GEMINI_LIVE_URL } from "../hosted-ai-api";
import {
  HOSTED_AI_VOICE_MONTHLY_SECONDS,
  VOICE_BLOCK_SECONDS,
  spendVoiceBlock,
  voiceBlocksRemaining,
  voiceQuotaFromRow,
  type LedgerRow,
} from "../hosted-ai-ledger";
import {
  DEFAULT_LIVE_MODEL,
  LIVE_LIMITS,
  inspectLiveClientMessage,
  resolveLiveModel,
  sanitizeLiveSetup,
} from "../hosted-ai-live";
import {
  BASE,
  FakeSocket,
  MemoryLedgerStore,
  USER_ID,
  call,
  captureLogs,
  createHarness,
  settle,
  type Harness,
} from "./hosted-ai-harness";

afterEach(() => {
  vi.restoreAllMocks();
});

const SETUP = {
  setup: {
    model: "models/anything-the-client-wants",
    generationConfig: {
      responseModalities: ["AUDIO"],
      speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Kore" } }, languageCode: "en-US" },
    },
    systemInstruction: { parts: [{ text: "You are Fud AI's assistant and coach." }] },
    tools: [
      {
        functionDeclarations: [
          {
            name: "log_water",
            description: "Log water.",
            parameters: { type: "object", properties: { amount: { type: "number" }, unit: { type: "string" } } },
          },
          { name: "get_app_state", description: "What the app shows.", parameters: { type: "object", properties: {} } },
        ],
      },
    ],
    inputAudioTranscription: {},
    outputAudioTranscription: {},
    contextWindowCompression: { slidingWindow: {} },
    sessionResumption: {},
    realtimeInputConfig: { automaticActivityDetection: { disabled: false } },
  },
};

const AUDIO = { realtimeInput: { audio: { mimeType: "audio/pcm;rate=16000", data: "AAAAAAAA" } } };
const FRAME = { realtimeInput: { video: { mimeType: "image/jpeg", data: "/9j/4AAQ" } } };

function liveRequest(headers: Record<string, string> = {}): Request {
  return new Request(`${BASE}/live`, {
    method: "GET",
    headers: { Upgrade: "websocket", "X-Fud-User-Id": USER_ID, "X-Fud-Platform": "ios", ...headers },
  });
}

function quotaRequest(): Request {
  return new Request(`${BASE}/quota`, { method: "GET", headers: { "X-Fud-User-Id": USER_ID } });
}

/** Creates the ledger row (as any earlier hosted request would) so a test can shape the balance. */
async function seedRow(harness: Harness, shape: Partial<LedgerRow> = {}): Promise<LedgerRow> {
  await call(harness, quotaRequest());
  return Object.assign(harness.store.only(), shape);
}

async function openSession(harness: Harness): Promise<{ app: FakeSocket; gemini: FakeSocket }> {
  const response = await call(harness, liveRequest());
  expect(response.status).toBe(101);
  return { app: harness.appSockets.at(-1)!, gemini: harness.upstreamSockets.at(-1)! };
}

/** Upgrade, setup, setupComplete, and block 0 paid: the conversation is live. */
async function startConversation(harness: Harness): Promise<{ app: FakeSocket; gemini: FakeSocket }> {
  const sockets = await openSession(harness);
  sockets.app.receiveJson(SETUP);
  sockets.gemini.receiveJson({ setupComplete: {} });
  await settle();
  return sockets;
}

function voiceUsed(harness: Harness): number {
  return harness.store.only().voice_seconds_used;
}

function bytesOf(text: string): ArrayBuffer {
  const bytes = new TextEncoder().encode(text);
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

/** Waits (in real time) until async request work reaches the point `condition` describes. */
async function until(condition: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 100 && !condition(); attempt += 1) await settle();
  expect(condition()).toBe(true);
}

describe("hosted voice relay: checks before the upgrade", () => {
  it("only upgrades GET requests that ask for a WebSocket", async () => {
    const harness = createHarness();
    let response = await call(harness, new Request(`${BASE}/live`, { method: "POST", headers: { "X-Fud-User-Id": USER_ID } }));
    expect(response.status).toBe(405);
    expect(response.headers.get("Allow")).toBe("GET");

    response = await call(harness, new Request(`${BASE}/live`, { headers: { "X-Fud-User-Id": USER_ID } }));
    expect(response.status).toBe(426);
    expect(response.headers.get("Upgrade")).toBe("websocket");
    await expect(response.json()).resolves.toEqual({ error: "upgrade_required" });
    expect(harness.calls).toHaveLength(0);
  });

  it("rejects missing or malformed user ids with 401 before RevenueCat or Gemini", async () => {
    const harness = createHarness();
    for (const id of ["", "alice@example.com", "$RCAnonymousID:0123"]) {
      const response = await call(harness, liveRequest({ "X-Fud-User-Id": id }));
      expect(response.status).toBe(401);
      await expect(response.json()).resolves.toEqual({ error: "invalid_user_id" });
    }
    expect(harness.calls).toHaveLength(0);
    expect(harness.upstreamSockets).toHaveLength(0);
  });

  it("returns 429 when a rate limiter trips", async () => {
    const harness = createHarness();
    harness.rateLimit.address = false;
    const response = await call(harness, liveRequest());
    expect(response.status).toBe(429);
    await expect(response.json()).resolves.toEqual({ error: "rate_limited" });
    expect(response.headers.get("Retry-After")).toBe("60");
    expect(harness.calls).toHaveLength(0);
  });

  it("returns 403 subscription_required without a verified plan, even with credits", async () => {
    const harness = createHarness({ revenueCat: { plan: "none", credits: [{ product: "credits_50", id: "t-1" }] } });
    const response = await call(harness, liveRequest({ "X-Fud-Plan": "pro" }));
    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toMatchObject({
      error: "subscription_required",
      voice: { month: "2026-09", secondsUsed: 0, secondsLimit: 0, creditBank: 50 },
    });
    expect(harness.upstreamSockets).toHaveLength(0);
  });

  it("returns 402 voice_quota_exceeded when the allowance and credits are used up", async () => {
    const harness = createHarness();
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1200 });
    const response = await call(harness, liveRequest());
    expect(response.status).toBe(402);
    await expect(response.json()).resolves.toMatchObject({
      error: "voice_quota_exceeded",
      voice: { month: "2026-09", secondsUsed: 1200, secondsLimit: 1200, creditBank: 0 },
    });
    expect(harness.calls.some((c) => c.url === GEMINI_LIVE_URL)).toBe(false);
  });

  it("lets credits alone open a session once the allowance is gone", async () => {
    const harness = createHarness({ revenueCat: { plan: "plus", credits: [{ product: "credits_50", id: "t-1" }] } });
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1200 });
    const response = await call(harness, liveRequest());
    expect(response.status).toBe(101);
  });

  it("returns 503 voice_unavailable when the Gemini key or the model setting is missing", async () => {
    const harness = createHarness();
    harness.env = { ...harness.env, GEMINI_API_KEY: "" };
    let response = await call(harness, liveRequest());
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({ error: "voice_unavailable" });

    harness.env = { ...harness.env, GEMINI_API_KEY: "gemini-key", HOSTED_AI_LIVE_MODEL: "models/bad model?key=x" };
    const logs = captureLogs();
    response = await call(harness, liveRequest());
    logs.restore();
    expect(response.status).toBe(503);
    expect(logs.lines.join("\n")).toContain("hosted_ai_live_misconfigured");
    expect(harness.calls.some((c) => c.url === GEMINI_LIVE_URL)).toBe(false);
  });

  it.each([
    ["Gemini rate-limits the handshake", { status: 429 }, "30"],
    ["Gemini fails", { status: 503 }, "30"],
    ["Gemini rejects the key", { status: 400, body: { error: { code: 400, status: "INVALID_ARGUMENT", message: "API key gemini-key not valid" } } }, null],
    ["the network fails", { throwName: "TypeError" }, "30"],
  ] as const)("returns 503 voice_unavailable when %s, logging no free text", async (_label, live, retryAfter) => {
    const harness = createHarness({ geminiLive: { ...live } });
    const logs = captureLogs();
    const response = await call(harness, liveRequest());
    logs.restore();
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({ error: "voice_unavailable" });
    expect(response.headers.get("Retry-After")).toBe(retryAfter);
    const joined = logs.lines.join("\n");
    expect(joined).toContain("hosted_ai_live_connect_failed");
    expect(joined).not.toContain("gemini-key");
    expect(joined).not.toContain("not valid");
    expect(harness.appSockets).toHaveLength(0);
  });

  it("gives up on a Gemini handshake after 10 seconds", async () => {
    const harness = createHarness({ geminiLive: { hang: true } });
    const pending = call(harness, liveRequest());
    await until(() => harness.timers.pending === 1);
    await harness.timers.advance(9_999);
    expect(harness.timers.pending).toBe(1);
    await harness.timers.advance(1);
    const response = await pending;
    expect(response.status).toBe(503);
    expect(response.headers.get("Retry-After")).toBe("30");
  });

  it("opens Gemini Live with the key in the x-goog-api-key header, never in the URL", async () => {
    const harness = createHarness();
    await openSession(harness);
    const upstream = harness.calls.find((c) => c.url === GEMINI_LIVE_URL);
    expect(upstream).toBeDefined();
    expect(upstream!.url).not.toMatch(/key/i);
    const headers = new Headers(upstream!.init?.headers);
    expect(headers.get("x-goog-api-key")).toBe("gemini-key");
    expect(headers.get("Upgrade")).toBe("websocket");
  });

  it("accepts both sockets as ArrayBuffer-binary and charges nothing before setupComplete", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    for (const socket of [app, gemini]) {
      expect(socket.accepted).toBe(true);
      expect(socket.binaryType).toBe("arraybuffer");
    }
    app.receiveJson(SETUP);
    await settle();
    expect(harness.store.voiceSpendAttempts).toBe(0);
    expect(voiceUsed(harness)).toBe(0);
  });
});

describe("hosted voice relay: setup", () => {
  it("pins the model and forwards only the allow-listed setup", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    app.receiveJson(SETUP);
    expect(gemini.sent).toHaveLength(1);
    expect(typeof gemini.sent[0]).toBe("string");
    expect(gemini.sentJson()[0]).toEqual({ setup: { ...SETUP.setup, model: `models/${DEFAULT_LIVE_MODEL}` } });
  });

  it("uses HOSTED_AI_LIVE_MODEL when it is set", async () => {
    const harness = createHarness();
    harness.env = { ...harness.env, HOSTED_AI_LIVE_MODEL: "models/gemini-live-next" };
    const { app, gemini } = await openSession(harness);
    app.receiveJson({ setup: {} });
    expect(gemini.sentJson()[0]).toEqual({ setup: { model: "models/gemini-live-next" } });
    expect(resolveLiveModel(undefined)).toBe("gemini-3.8-live");
    expect(resolveLiveModel("  ")).toBe("gemini-3.8-live");
    expect(resolveLiveModel("gemini-x/../y")).toBeNull();
  });

  it("accepts a setup sent as a binary frame", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    app.receive(bytesOf(JSON.stringify(SETUP)));
    expect(gemini.sentJson()[0]).toMatchObject({ setup: { model: `models/${DEFAULT_LIVE_MODEL}` } });
    expect(app.closeCalls).toHaveLength(0);
  });

  it("accepts a setup at the documented limits", () => {
    const declarations = Array.from({ length: 64 }, (_, index) => ({
      name: `tool_${index}`,
      description: "d".repeat(800),
      parameters: { type: "object", properties: { value: { type: "string" } } },
    }));
    const result = sanitizeLiveSetup(
      {
        setup: {
          systemInstruction: { role: "system", parts: [{ text: "a".repeat(30_000) }, { text: "b".repeat(30_000) }] },
          tools: [{ functionDeclarations: declarations.slice(0, 32) }, { functionDeclarations: declarations.slice(32) }],
          contextWindowCompression: { triggerTokens: "25600", slidingWindow: { targetTokens: 12800 } },
          sessionResumption: { handle: "resume-handle-1" },
          inputAudioTranscription: { languageCodes: ["en-US", "hi-IN"] },
          realtimeInputConfig: {
            automaticActivityDetection: {
              startOfSpeechSensitivity: "START_SENSITIVITY_LOW",
              endOfSpeechSensitivity: "END_SENSITIVITY_HIGH",
              prefixPaddingMs: 20,
              silenceDurationMs: 500,
            },
            activityHandling: "START_OF_ACTIVITY_INTERRUPTS",
            turnCoverage: "TURN_INCLUDES_ONLY_ACTIVITY",
          },
          generationConfig: { responseModalities: ["AUDIO", "TEXT"] },
        },
      },
      "gemini-3.8-live"
    );
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    const forwarded = JSON.parse(result.message) as { setup: Record<string, unknown> };
    expect(forwarded.setup.systemInstruction).toEqual({ parts: [{ text: "a".repeat(30_000) }, { text: "b".repeat(30_000) }] });
    expect(forwarded.setup.contextWindowCompression).toEqual({ triggerTokens: "25600", slidingWindow: { targetTokens: 12800 } });
  });

  it("accepts NON_BLOCKING function declarations", () => {
    const result = sanitizeLiveSetup(
      { setup: { tools: [{ functionDeclarations: [{ name: "pick_photos", behavior: "NON_BLOCKING" }] }] } },
      "m"
    );
    expect(result).toEqual({
      ok: true,
      message: JSON.stringify({
        setup: { model: "models/m", tools: [{ functionDeclarations: [{ name: "pick_photos", behavior: "NON_BLOCKING" }] }] },
      }),
    });
  });

  it("forwards the selected speaker and no-interruption policy without rewriting them", () => {
    const realtimeInputConfig = {
      automaticActivityDetection: {
        startOfSpeechSensitivity: "START_SENSITIVITY_LOW",
        endOfSpeechSensitivity: "END_SENSITIVITY_LOW",
        prefixPaddingMs: 240,
        silenceDurationMs: 700,
      },
      activityHandling: "NO_INTERRUPTION",
    };
    const speechConfig = { voiceConfig: { prebuiltVoiceConfig: { voiceName: "Leda" } } };
    const result = sanitizeLiveSetup({ setup: {
      generationConfig: { responseModalities: ["AUDIO"], speechConfig },
      realtimeInputConfig,
    } }, "gemini-3.8-live");
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(JSON.parse(result.message).setup).toMatchObject({ realtimeInputConfig, generationConfig: { speechConfig } });
  });

  const manyDeclarations = Array.from({ length: 65 }, (_, index) => ({ name: `tool_${index}` }));
  const bigDeclarations = Array.from({ length: 20 }, (_, index) => ({ name: `tool_${index}`, description: "x".repeat(3_400) }));

  it.each([
    ["a first message that is not setup", AUDIO, "setup_required:message"],
    ["two message types", { setup: {}, realtimeInput: {} }, "setup_required:message"],
    ["a setup that is not an object", { setup: [] }, "invalid_type:setup"],
    ["an unknown setup field", { setup: { proactivity: { proactiveAudio: true } } }, "unsupported_field:setup.proactivity"],
    ["Google Search", { setup: { tools: [{ googleSearch: {} }] } }, "unsupported_field:setup.tools[0].googleSearch"],
    ["code execution", { setup: { tools: [{ codeExecution: {} }] } }, "unsupported_field:setup.tools[0].codeExecution"],
    ["URL context", { setup: { tools: [{ functionDeclarations: [{ name: "a" }], urlContext: {} }] } }, "unsupported_field:setup.tools[0].urlContext"],
    ["a sampling knob", { setup: { generationConfig: { temperature: 2 } } }, "unsupported_field:setup.generationConfig.temperature"],
    ["thinking config", { setup: { generationConfig: { thinkingConfig: { thinkingBudget: 9999 } } } }, "unsupported_field:setup.generationConfig.thinkingConfig"],
    ["a video modality", { setup: { generationConfig: { responseModalities: ["VIDEO"] } } }, "unsupported_value:setup.generationConfig.responseModalities[0]"],
    ["multi-speaker voices", { setup: { generationConfig: { speechConfig: { multiSpeakerVoiceConfig: {} } } } }, "unsupported_field:setup.generationConfig.speechConfig.multiSpeakerVoiceConfig"],
    ["a malformed voice name", { setup: { generationConfig: { speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: "../Kore" } } } } } }, "invalid_value:setup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName"],
    ["a 60 001-character instruction", { setup: { systemInstruction: { parts: [{ text: "a".repeat(60_000) }, { text: "b" }] } } }, "too_long:setup.systemInstruction"],
    ["an image in the instruction", { setup: { systemInstruction: { parts: [{ inlineData: { mimeType: "image/png", data: "AA" } }] } } }, "unsupported_field:setup.systemInstruction.parts[0].inlineData"],
    ["65 function declarations", { setup: { tools: [{ functionDeclarations: manyDeclarations }] } }, "too_many_function_declarations:setup.tools"],
    ["more than 64 KB of tools", { setup: { tools: [{ functionDeclarations: bigDeclarations }] } }, "too_large:setup.tools"],
    ["a malformed function name", { setup: { tools: [{ functionDeclarations: [{ name: "drop table" }] }] } }, "invalid_function_name:setup.tools[0].functionDeclarations[0].name"],
    ["duplicate function names", { setup: { tools: [{ functionDeclarations: [{ name: "a" }, { name: "a" }] }] } }, "duplicate_function_name:setup.tools[0].functionDeclarations[1].name"],
    ["an unknown declaration field", { setup: { tools: [{ functionDeclarations: [{ name: "a", response: {} }] }] } }, "unsupported_field:setup.tools[0].functionDeclarations[0].response"],
    ["transcription extras", { setup: { inputAudioTranscription: { diarization: true } } }, "unsupported_field:setup.inputAudioTranscription.diarization"],
    ["a negative token budget", { setup: { contextWindowCompression: { triggerTokens: -1 } } }, "out_of_range:setup.contextWindowCompression.triggerTokens"],
    ["a non-string resumption handle", { setup: { sessionResumption: { handle: 7 } } }, "invalid_type:setup.sessionResumption.handle"],
    ["a lower-case activity enum", { setup: { realtimeInputConfig: { activityHandling: "no_interruption" } } }, "invalid_value:setup.realtimeInputConfig.activityHandling"],
  ] as const)("rejects %s", async (_label, message, detail) => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    const logs = captureLogs();
    app.receiveJson(message);
    logs.restore();
    expect(app.fudVoice()).toEqual([{ error: "invalid_setup", detail }]);
    expect(app.closedWith).toBe(1008);
    expect(gemini.sent).toHaveLength(0);
    expect(gemini.closedWith).toBe(1000);
    expect(logs.lines.join("\n")).toContain("hosted_ai_live_rejected");
  });

  it("rejects malformed and oversized setup frames", async () => {
    const logs = captureLogs();
    for (const [frame, detail] of [
      ["{not json", "invalid_json:message"],
      [JSON.stringify({ setup: { systemInstruction: { parts: [{ text: "a".repeat(LIVE_LIMITS.maxMessageBytes) }] } } }), "too_large:message"],
    ] as const) {
      const harness = createHarness();
      const { app } = await openSession(harness);
      app.receive(frame);
      expect(app.fudVoice()).toEqual([{ error: "invalid_setup", detail }]);
      expect(app.closedWith).toBe(1008);
    }
    logs.restore();
  });

  it("closes with setup_timeout (1008) when the app sends no setup within 10 s", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    captureLogs();
    await harness.timers.advance(9_999);
    expect(app.closeCalls).toHaveLength(0);
    await harness.timers.advance(1);
    expect(app.fudVoice()).toEqual([{ error: "setup_timeout" }]);
    expect(app.closedWith).toBe(1008);
    expect(gemini.closedWith).toBe(1000);
  });

  it("treats a missing setupComplete as an upstream failure (1011) without charging", async () => {
    const harness = createHarness();
    const { app } = await openSession(harness);
    captureLogs();
    app.receiveJson(SETUP);
    await harness.timers.advance(15_000);
    expect(app.fudVoice()).toEqual([{ error: "upstream_error" }]);
    expect(app.closedWith).toBe(1011);
    expect(harness.store.voiceSpendAttempts).toBe(0);
  });
});

describe("hosted voice relay: messages", () => {
  it("delivers setupComplete, then the balance, then relays both ways", async () => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    expect(app.sentJson()).toEqual([{ setupComplete: {} }, { fudVoice: { secondsRemaining: 1200 } }]);

    const audio = JSON.stringify(AUDIO);
    const content = JSON.stringify({ clientContent: { turns: [{ role: "user", parts: [{ text: "Confirmed." }] }], turnComplete: true } });
    const tool = bytesOf(JSON.stringify({ toolResponse: { functionResponses: [{ id: "c1", name: "log_water", response: { ok: true } }] } }));
    app.receive(audio);
    app.receive(content);
    app.receive(tool);
    expect(gemini.sent.slice(1)).toEqual([audio, content, tool]);
    expect(gemini.sent[3]).toBe(tool);

    const serverText = JSON.stringify({ serverContent: { outputTranscription: { text: "Logged." } } });
    const serverBinary = bytesOf(JSON.stringify({ serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: "audio/pcm;rate=24000", data: "AAAA" } }] } } }));
    gemini.receive(serverText);
    gemini.receive(serverBinary);
    expect(app.sent.slice(2)).toEqual([serverText, serverBinary]);
    expect(app.sent[3]).toBe(serverBinary);
  });

  it("forwards a binary setupComplete as binary", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    app.receiveJson(SETUP);
    const setupComplete = bytesOf('{"setupComplete":{}}');
    gemini.receive(setupComplete);
    await settle();
    expect(app.sent[0]).toBe(setupComplete);
  });

  it("holds input sent before the first block is paid, then forwards it in order", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    app.receiveJson(SETUP);
    app.receiveJson(AUDIO);
    app.receiveJson(FRAME);
    expect(gemini.sent).toHaveLength(1);

    gemini.receiveJson({ setupComplete: {} });
    gemini.receiveJson({ sessionResumptionUpdate: { newHandle: "h1", resumable: true } });
    expect(app.sent).toHaveLength(0);
    await settle();
    expect(app.sentJson()).toEqual([
      { setupComplete: {} },
      { fudVoice: { secondsRemaining: 1200 } },
      { sessionResumptionUpdate: { newHandle: expect.stringMatching(/^fudv1\./), resumable: true } },
    ]);
    expect(gemini.sentJson().slice(1)).toEqual([AUDIO, FRAME]);
  });

  it("wraps binary resumption updates, preserves following frames and unwraps on reconnect", async () => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    gemini.receive(bytesOf(JSON.stringify({ sessionResumptionUpdate: { newHandle: "provider-handle", resumable: true } })));
    const later = JSON.stringify({ goAway: { timeLeft: "1s" } });
    gemini.receive(later);
    gemini.remoteClose(1000);
    await until(() => app.closedWith === 1000);
    expect(app.sent[2]).toBeInstanceOf(ArrayBuffer);
    const update = app.sentJson()[2]!.sessionResumptionUpdate as { newHandle: string };
    expect(update.newHandle).toMatch(/^fudv1\./);
    expect(update.newHandle).not.toBe("provider-handle");
    expect(app.sent[3]).toBe(later);
    const reconnect = await openSession(harness);
    reconnect.app.receiveJson({ setup: { sessionResumption: { handle: update.newHandle } } });
    await until(() => reconnect.gemini.sent.length > 0);
    expect(reconnect.gemini.sentJson()[0]).toMatchObject({ setup: { sessionResumption: { handle: "provider-handle" } } });
  });

  it.each(["text", "binary"])("caps accumulated %s provider output while the first charge is pending", async (format) => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    const original = harness.store.trySpendVoice.bind(harness.store);
    let releaseCharge!: () => void;
    const chargePending = new Promise<void>((resolve) => { releaseCharge = resolve; });
    vi.spyOn(harness.store, "trySpendVoice").mockImplementation(async (...args) => {
      await chargePending;
      return original(...args);
    });
    app.receiveJson(SETUP);
    gemini.receiveJson({ setupComplete: {} });
    const text = JSON.stringify({ serverContent: { outputTranscription: { text: "食".repeat(200_000) } } });
    const bytes = bytesOf(text);
    const frame = format === "text" ? text : bytes;
    const fittingFrames = Math.floor(LIVE_LIMITS.maxPendingUpstreamBytes / bytes.byteLength);
    for (let index = 0; index < fittingFrames; index += 1) gemini.receive(frame);
    expect(app.sent).toHaveLength(0);
    expect(app.closeCalls).toHaveLength(0);

    const logs = captureLogs();
    gemini.receive(frame);
    logs.restore();
    expect(app.closedWith).toBe(1011);
    expect(app.sentJson()).toEqual([{ fudVoice: { error: "upstream_error" } }]);
    expect(gemini.closedWith).toBe(1000);
    expect(logs.lines.join("\n")).toContain("starting_buffer_limit");
    expect(logs.lines.join("\n")).not.toContain("食");
    expect(harness.timers.pending).toBe(0);

    // A late billing result must not reopen or flush the discarded conversation.
    releaseCharge();
    await Promise.all(harness.background);
    await settle();
    expect(app.sentJson()).toEqual([{ fudVoice: { error: "upstream_error" } }]);
    expect(harness.timers.pending).toBe(0);
  });

  it("rejects a handle from another subscriber before sending setup to Gemini", async () => {
    const harness = createHarness();
    const first = await startConversation(harness);
    first.gemini.receiveJson({ sessionResumptionUpdate: { newHandle: "private-provider-handle", resumable: true } });
    await until(() => first.app.sent.length === 3);
    const token = (first.app.sentJson()[2]!.sessionResumptionUpdate as { newHandle: string }).newHandle;
    const response = await call(harness, liveRequest({ "X-Fud-User-Id": "$RCAnonymousID:11111111111111111111111111111111" }));
    expect(response.status).toBe(101);
    const otherApp = harness.appSockets.at(-1)!;
    const otherGemini = harness.upstreamSockets.at(-1)!;
    const logs = captureLogs();
    otherApp.receiveJson({ setup: { sessionResumption: { handle: token } } });
    await until(() => otherApp.closedWith !== undefined);
    logs.restore();
    expect(otherApp.closedWith).toBe(1008);
    expect(otherGemini.sent).toEqual([]);
    expect(logs.lines.join("\n")).not.toContain(token);
    expect(logs.lines.join("\n")).not.toContain("private-provider-handle");
  });

  it.each([
    ["a second setup", JSON.stringify(SETUP), "unsupported_message:setup"],
    ["an unknown message type", '{"<script>alert(1)</script>":{}}', "unsupported_message:scriptalert1script"],
    ["two message types", JSON.stringify({ realtimeInput: {}, toolResponse: {} }), "one_message_type_required:message"],
    ["a non-object body", JSON.stringify({ realtimeInput: [] }), "invalid_type:realtimeInput"],
    ["a JSON array", "[]", "invalid_type:message"],
    ["invalid JSON", "{", "invalid_json:message"],
    [
      "a file reference",
      JSON.stringify({ clientContent: { turns: [{ role: "user", parts: [{ fileData: { fileUri: "https://youtu.be/x" } }] }] } }),
      "unsupported_field:clientContent.turns[0].parts[0].fileData",
    ],
    [
      "a file reference in a tool response",
      JSON.stringify({ toolResponse: { functionResponses: [{ id: "1", name: "a", response: {}, parts: [{ fileData: {} }] }] } }),
      "unsupported_field:toolResponse.functionResponses[0].parts[0].fileData",
    ],
    ["a message over 1 MiB", JSON.stringify({ realtimeInput: { text: "a".repeat(LIVE_LIMITS.maxMessageBytes) } }), "too_large:message"],
  ] as const)("closes with invalid_message (1008) on %s", async (_label, frame, detail) => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    const logs = captureLogs();
    app.receive(frame);
    logs.restore();
    expect(app.fudVoice().at(-1)).toEqual({ error: "invalid_message", detail });
    expect(app.closedWith).toBe(1008);
    expect(gemini.sent).toHaveLength(1);
    expect(gemini.closedWith).toBe(1000);
  });

  it("rejects binary frames that are not UTF-8", async () => {
    const harness = createHarness();
    const { app } = await startConversation(harness);
    captureLogs();
    app.receive(new Uint8Array([0x7b, 0xff, 0xfe, 0x7d]).buffer);
    expect(app.fudVoice().at(-1)).toEqual({ error: "invalid_message", detail: "invalid_utf8:message" });
  });

  it("counts multi-byte text by its UTF-8 size", async () => {
    const harness = createHarness();
    const { app } = await startConversation(harness);
    captureLogs();
    // 350 000 three-byte characters: under 1 MiB of UTF-16 code units, over 1 MiB of UTF-8.
    app.receive(JSON.stringify({ realtimeInput: { text: "食".repeat(350_000) } }));
    expect(app.fudVoice().at(-1)).toEqual({ error: "invalid_message", detail: "too_large:message" });
  });

  it("allows real-time audio plus a frame a second indefinitely", async () => {
    const harness = createHarness({ revenueCat: { plan: "pro" } });
    const { app, gemini } = await startConversation(harness);
    const chunk = JSON.stringify({ realtimeInput: { audio: { mimeType: "audio/pcm;rate=16000", data: "A".repeat(1_400) } } });
    const frame = JSON.stringify({ realtimeInput: { video: { mimeType: "image/jpeg", data: "A".repeat(200_000) } } });
    for (let second = 0; second < 60; second += 1) {
      for (let tick = 0; tick < 25; tick += 1) app.receive(chunk);
      app.receive(frame);
      await harness.timers.advance(1_000);
    }
    expect(app.closeCalls).toHaveLength(0);
    expect(gemini.sent).toHaveLength(1 + 60 * 26);
  });

  it("closes with rate_limited (1008) on input far above real time", async () => {
    const logs = captureLogs();
    const harness = createHarness();
    const { app } = await startConversation(harness);
    const big = JSON.stringify({ realtimeInput: { audio: { mimeType: "audio/pcm;rate=16000", data: "A".repeat(1_000_000) } } });
    for (let index = 0; index < 5; index += 1) app.receive(big);
    expect(app.fudVoice().at(-1)).toEqual({ error: "rate_limited" });
    expect(app.closedWith).toBe(1008);

    const frames = createHarness();
    const session = await startConversation(frames);
    for (let index = 0; index < LIVE_LIMITS.videoFrameBurst; index += 1) session.app.receiveJson(FRAME);
    expect(session.app.closeCalls).toHaveLength(0);
    session.app.receiveJson(FRAME);
    expect(session.app.fudVoice().at(-1)).toEqual({ error: "rate_limited" });
    logs.restore();
  });

  it("counts UTF-8 bytes in the input rate budget even for frames below the size limit", async () => {
    const harness = createHarness();
    const { app } = await startConversation(harness);
    const logs = captureLogs();
    const frame = JSON.stringify({ realtimeInput: { text: "食".repeat(330_000) } });
    for (let index = 0; index < 5; index += 1) app.receive(frame);
    logs.restore();
    expect(app.fudVoice().at(-1)).toEqual({ error: "rate_limited" });
    expect(app.closedWith).toBe(1008);
  });

  it("counts deprecated mediaChunks images as video frames", () => {
    expect(
      inspectLiveClientMessage({
        realtimeInput: {
          mediaChunks: [
            { mimeType: "audio/pcm", data: "" },
            { mimeType: "image/jpeg", data: "" },
            { mimeType: "image/png", data: "" },
          ],
        },
      })
    ).toEqual({ ok: true, videoFrames: 2 });
    expect(inspectLiveClientMessage(FRAME)).toEqual({ ok: true, videoFrames: 1 });
    expect(inspectLiveClientMessage(AUDIO)).toEqual({ ok: true, videoFrames: 0 });
  });
});

describe("hosted voice relay: metering", () => {
  it("charges a 15-second block at setupComplete and at the start of every block after it", async () => {
    const harness = createHarness();
    await startConversation(harness);
    expect(voiceUsed(harness)).toBe(15);
    await harness.timers.advance(14_999);
    expect(voiceUsed(harness)).toBe(15);
    await harness.timers.advance(1);
    expect(voiceUsed(harness)).toBe(30);
    await harness.timers.advance(45_000);
    expect(voiceUsed(harness)).toBe(75);
    expect(harness.store.only().voice_month).toBe("2026-09");
    expect(harness.store.only().credits_spent).toBe(0);
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("sends secondsRemaining after setup and every 60 s", async () => {
    const harness = createHarness();
    const { app } = await startConversation(harness);
    await harness.timers.advance(59_000);
    expect(app.fudVoice()).toEqual([{ secondsRemaining: 1200 }]);
    await harness.timers.advance(1_000);
    expect(app.fudVoice()).toEqual([{ secondsRemaining: 1200 }, { secondsRemaining: 1140 }]);
    await harness.timers.advance(60_000);
    expect(app.fudVoice().at(-1)).toEqual({ secondsRemaining: 1080 });
  });

  it("falls back to the credit bank at 1 credit per block, then closes with 4402", async () => {
    const harness = createHarness({ revenueCat: { plan: "plus", credits: [{ product: "credits_50", id: "t-1" }] } });
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1185, credits_spent: 47 });
    const { app, gemini } = await startConversation(harness);
    // Block 0 used the last allowance block; 3 credits remain.
    expect(harness.store.only()).toMatchObject({ voice_seconds_used: 1200, credits_spent: 47 });
    expect(app.fudVoice()).toEqual([{ secondsRemaining: 60 }]);

    await harness.timers.advance(15_000);
    expect(harness.store.only()).toMatchObject({ voice_seconds_used: 1200, credits_spent: 48 });
    await harness.timers.advance(30_000);
    expect(harness.store.only().credits_spent).toBe(50);
    expect(app.closeCalls).toHaveLength(0);

    await harness.timers.advance(15_000);
    expect(app.fudVoice().at(-1)).toEqual({ error: "voice_quota_exceeded" });
    expect(app.closedWith).toBe(4402);
    expect(gemini.closedWith).toBe(1000);
    expect(harness.store.only().credits_spent).toBe(50);
    expect(harness.timers.pending).toBe(0);
  });

  it("closes with 4402 at setupComplete when the balance went to another session meanwhile", async () => {
    const harness = createHarness();
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1185 });
    const { app, gemini } = await openSession(harness);
    // Another session paid the last block (bumping the version) after this one was admitted.
    const row = harness.store.only();
    Object.assign(row, { voice_seconds_used: 1200, version: row.version + 1 });
    app.receiveJson(SETUP);
    gemini.receiveJson({ setupComplete: {} });
    await settle();
    expect(app.sentJson()).toEqual([{ fudVoice: { error: "voice_quota_exceeded" } }]);
    expect(app.closedWith).toBe(4402);
  });

  it("rolls the allowance over at the start of a new UTC month", async () => {
    const harness = createHarness({ revenueCat: { plan: "plus", credits: [{ product: "credits_50", id: "t-1" }] } });
    harness.clock.now = new Date("2026-09-30T23:59:50.000Z");
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1200 });
    await startConversation(harness);
    expect(harness.store.only()).toMatchObject({ voice_month: "2026-09", voice_seconds_used: 1200, credits_spent: 1 });

    await harness.timers.advance(15_000);
    expect(harness.store.only()).toMatchObject({ voice_month: "2026-10", voice_seconds_used: 15, credits_spent: 1 });
    const quota = await call(harness, quotaRequest());
    await expect(quota.json()).resolves.toMatchObject({
      voice: { month: "2026-10", secondsUsed: 15, secondsLimit: 1200, creditBank: 49 },
    });
  });

  it("ends the session after 15 minutes with session_limit (4408), having charged exactly 60 blocks", async () => {
    const harness = createHarness({ revenueCat: { plan: "pro" } });
    const { app, gemini } = await startConversation(harness);
    await harness.timers.advance(899_999);
    expect(app.closeCalls).toHaveLength(0);
    await harness.timers.advance(1);
    expect(app.fudVoice().at(-1)).toEqual({ error: "session_limit" });
    expect(app.closedWith).toBe(4408);
    expect(gemini.closedWith).toBe(1000);
    expect(voiceUsed(harness)).toBe(LIVE_LIMITS.sessionSeconds);
    expect(app.fudVoice().filter((message) => "secondsRemaining" in message)).toHaveLength(15);
    expect(harness.timers.pending).toBe(0);
  });

  it("retries the compare-and-swap when a concurrent request bumps the version", async () => {
    const harness = createHarness();
    await seedRow(harness);
    harness.store.voiceConflictOnce = true;
    await startConversation(harness);
    expect(harness.store.voiceSpendAttempts).toBe(2);
    expect(voiceUsed(harness)).toBe(15);
  });

  it("keeps two sessions of one user from spending the same blocks", async () => {
    const harness = createHarness();
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1170 });
    const first = await openSession(harness);
    const second = await openSession(harness);
    for (const session of [first, second]) {
      session.app.receiveJson(SETUP);
      session.gemini.receiveJson({ setupComplete: {} });
    }
    await settle();
    await harness.timers.advance(15_000);
    expect(voiceUsed(harness)).toBe(1200);
    expect([first.app.closedWith, second.app.closedWith]).toEqual([4402, 4402]);
  });

  it("retries a block charge D1 rejects, and closes with 1011 when D1 stays down", async () => {
    const harness = createHarness();
    await seedRow(harness);
    harness.store.voiceFailures = 2;
    const { app } = await startConversation(harness);
    expect(voiceUsed(harness)).toBe(15);
    expect(app.sentJson()[0]).toEqual({ setupComplete: {} });

    harness.store.voiceFailures = 3;
    const logs = captureLogs();
    await harness.timers.advance(15_000);
    logs.restore();
    expect(app.fudVoice().at(-1)).toEqual({ error: "voice_unavailable" });
    expect(app.closedWith).toBe(1011);
    expect(logs.lines.join("\n")).toContain("hosted_ai_live_charge_failed");
  });

  it("hands every block charge to waitUntil so a disconnect cannot cancel it", async () => {
    const harness = createHarness();
    await startConversation(harness);
    await harness.timers.advance(30_000);
    expect(harness.background).toHaveLength(3);
    await Promise.all(harness.background);
  });

  it.each([0, 49])("does not debit twice when a block write commits and its response is lost (spent %i)", async (creditsSpent) => {
    const harness = createHarness({ revenueCat: { plan: "plus", credits: [{ product: "credits_50", id: "t-1" }] } });
    await seedRow(harness, { voice_month: "2026-09", voice_seconds_used: 1200, credits_spent: creditsSpent });
    const original = harness.store.trySpendVoice.bind(harness.store);
    let failAfterCommit = true;
    vi.spyOn(harness.store, "trySpendVoice").mockImplementation(async (...args) => {
      const committed = await original(...args);
      if (committed && failAfterCommit) {
        failAfterCommit = false;
        throw new Error("D1_ERROR: network error after commit");
      }
      return committed;
    });
    const { app } = await startConversation(harness);
    expect(harness.store.only().credits_spent).toBe(creditsSpent + 1);
    expect(app.sentJson()[0]).toEqual({ setupComplete: {} });
  });

  it("closes both sockets when a later block write never settles", async () => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    vi.spyOn(harness.store, "trySpendVoice").mockImplementation(() => new Promise(() => {}));
    await harness.timers.advance(20_000);
    expect(app.closedWith).toBe(1011);
    expect(app.fudVoice().at(-1)).toEqual({ error: "voice_unavailable" });
    expect(gemini.closedWith).toBe(1000);
    expect(harness.timers.pending).toBe(0);
  });
});

describe("hosted voice relay: closing", () => {
  it("closes upstream when the app closes, and stops metering", async () => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    app.remoteClose(1000);
    expect(gemini.closedWith).toBe(1000);
    await harness.timers.advance(60_000);
    expect(voiceUsed(harness)).toBe(15);
    expect(harness.timers.pending).toBe(0);
  });

  it("ends normally (1000) when Gemini closes a live conversation cleanly", async () => {
    const harness = createHarness();
    const { app } = await startConversation(harness);
    harness.upstreamSockets[0]!.remoteClose(1000);
    expect(app.closedWith).toBe(1000);
    expect(app.fudVoice()).toEqual([{ secondsRemaining: 1200 }]);
    await harness.timers.advance(60_000);
    expect(voiceUsed(harness)).toBe(15);
  });

  it.each([1011, 1007, 1006])(
    "closes with 1011 and upstream_error when Gemini closes with %i, relaying no reason text",
    async (code) => {
      const harness = createHarness();
      const { app, gemini } = await startConversation(harness);
      const logs = captureLogs();
      gemini.remoteClose(code, "API key gemini-key: user said my secret meal");
      logs.restore();
      expect(app.fudVoice().at(-1)).toEqual({ error: "upstream_error", upstreamCode: code });
      expect(app.closedWith).toBe(1011);
      const everything = [...logs.lines, ...app.sent.map(String)].join("\n");
      expect(everything).not.toContain("secret meal");
      expect(everything).not.toContain("gemini-key");
      await harness.timers.advance(60_000);
      expect(voiceUsed(harness)).toBe(15);
    }
  );

  it("treats a clean Gemini close before setupComplete as an upstream failure", async () => {
    const harness = createHarness();
    const { app, gemini } = await openSession(harness);
    captureLogs();
    app.receiveJson(SETUP);
    gemini.remoteClose(1000);
    expect(app.fudVoice()).toEqual([{ error: "upstream_error", upstreamCode: 1000 }]);
    expect(app.closedWith).toBe(1011);
  });

  it("closes upstream when the app can no longer be reached", async () => {
    const harness = createHarness();
    const { app, gemini } = await startConversation(harness);
    app.readyState = 3;
    gemini.receiveJson({ serverContent: { turnComplete: true } });
    expect(gemini.closedWith).toBe(1000);
    await harness.timers.advance(60_000);
    expect(voiceUsed(harness)).toBe(15);
  });
});

describe("voice ledger math", () => {
  const row = (shape: Partial<LedgerRow> = {}): LedgerRow => ({
    user_id_hash: "h".repeat(64),
    plan: "plus",
    credits_granted: 0,
    credits_spent: 0,
    usage_day: "2026-09-13",
    daily_used: 0,
    entitlement_verified_at: "2026-09-13T00:00:00.000Z",
    version: 0,
    voice_month: "",
    voice_seconds_used: 0,
    ...shape,
  });
  const now = new Date("2026-09-13T12:00:00.000Z");

  it("derives the monthly snapshot per plan and resets it in a new month", () => {
    expect(HOSTED_AI_VOICE_MONTHLY_SECONDS).toEqual({ none: 0, plus: 1200, pro: 6000 });
    expect(voiceQuotaFromRow(row({ voice_month: "2026-09", voice_seconds_used: 300 }), now)).toEqual({
      month: "2026-09",
      secondsUsed: 300,
      secondsLimit: 1200,
      creditBank: 0,
    });
    expect(voiceQuotaFromRow(row({ voice_month: "2026-08", voice_seconds_used: 1200 }), now).secondsUsed).toBe(0);
    expect(voiceQuotaFromRow(row({ plan: "pro", credits_granted: 50, credits_spent: 60 }), now)).toMatchObject({
      secondsLimit: 6000,
      creditBank: 0,
    });
  });

  it("counts whole payable blocks: allowance first, then 1 credit each", () => {
    const month = "2026-09";
    expect(voiceBlocksRemaining({ month, secondsUsed: 0, secondsLimit: 1200, creditBank: 0 })).toBe(80);
    expect(voiceBlocksRemaining({ month, secondsUsed: 1190, secondsLimit: 1200, creditBank: 2 })).toBe(2);
    expect(voiceBlocksRemaining({ month, secondsUsed: 3000, secondsLimit: 1200, creditBank: 0 })).toBe(0);
    expect(VOICE_BLOCK_SECONDS).toBe(15);
  });

  it("spends allowance, then credits, then reports quota_exceeded", async () => {
    const store = new MemoryLedgerStore();
    const created = await store.upsertEntitlement("h".repeat(64), { plan: "plus", creditsGranted: 1 }, now.toISOString());
    Object.assign(store.rows.get(created.user_id_hash)!, { voice_month: "2026-09", voice_seconds_used: 1185 });
    const context = { store, now: () => now, verify: async () => ({ plan: "plus" as const, creditsGranted: 1 }) };

    let current = (await store.get(created.user_id_hash))!;
    const first = await spendVoiceBlock(context, current.user_id_hash, current);
    expect(first).toMatchObject({ status: "spent", source: "allowance", voice: { secondsUsed: 1200, creditBank: 1 } });
    current = first.status === "spent" ? first.row : current;

    const second = await spendVoiceBlock(context, current.user_id_hash, current);
    expect(second).toMatchObject({ status: "spent", source: "credits", voice: { secondsUsed: 1200, creditBank: 0 } });
    current = second.status === "spent" ? second.row : current;

    const third = await spendVoiceBlock(context, current.user_id_hash, current);
    expect(third).toEqual({
      status: "quota_exceeded",
      voice: { month: "2026-09", secondsUsed: 1200, secondsLimit: 1200, creditBank: 0 },
    });
    expect(store.only()).toMatchObject({ voice_seconds_used: 1200, credits_spent: 1, version: 2 });
  });
});
