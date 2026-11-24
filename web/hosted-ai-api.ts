/**
 * Fud AI hosted path — proxies Gemini Flash-Lite + Deepgram STT for Plus/Pro
 * subscribers, and relays the live voice assistant to Gemini Live
 * (`GET /live`, WebSocket; session logic in hosted-ai-live.ts).
 *
 * Security model (see services/hosted-ai/README.md):
 *   - No shared client secret. The app only sends its RevenueCat app user id.
 *   - The Worker verifies the subscriber's plan with the RevenueCat REST API
 *     (secret key in Worker env), caches it in D1, and never trusts a
 *     client-asserted plan.
 *   - Every upstream round-trip is metered against a server-side ledger
 *     (daily pool → credit bank), so reinstalls/backups cannot reset quota.
 *   - Cloudflare rate limiters cap requests per user and per address.
 *   - `/gemini` bodies pass a strict allow-list before being forwarded.
 *   - Requests are fully validated (provider config + body) *before* the
 *     ledger is charged, so only genuine upstream failures need a refund.
 *   - Upstream error bodies are never echoed to callers and never logged
 *     verbatim; only structured status/code fields reach the logs.
 *   - Per-action cost is bounded: output tokens are capped, transcription
 *     audio is limited by measured duration, and upstream calls the provider
 *     may still bill (timeouts, empty 200s) are not refunded.
 *   - The voice relay passes the same identity, rate-limit and plan checks,
 *     plus a voice balance check, before upgrading; failures are plain JSON
 *     responses. It is metered by time (15-second blocks), not per action.
 */

import { audioContainerForMimeType, measureAudioDurationSeconds } from "./hosted-ai-audio";
import { GEMINI_REQUEST_LIMITS, sanitizeGeminiRequestBody } from "./hosted-ai-gemini-request";
import { createLiveResumptionCodec } from "./hosted-ai-live-resumption";
import {
  D1LedgerStore,
  fetchRevenueCatEntitlement,
  loadVerifiedLedger,
  quotaFromRow,
  refundToLedger,
  sha256Hex,
  spendFromLedger,
  voiceBlocksRemaining,
  voiceQuotaFromRow,
  type LedgerContext,
  type LedgerStore,
  type QuotaSnapshot,
  type SpendReceipt,
} from "./hosted-ai-ledger";
import {
  createWebSocketPair,
  resolveLiveModel,
  setRuntimeTimer,
  startLiveRelay,
  webSocketUpgradeResponse,
} from "./hosted-ai-live";

export const HOSTED_AI_API_PREFIX = "/api/hosted-ai/v1";

/** Gemini Live endpoint; the key goes in the `x-goog-api-key` header, never in this URL. */
export const GEMINI_LIVE_URL =
  "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
const LIVE_CONNECT_TIMEOUT_MS = 10_000;

const HOSTED_GEMINI_MODEL = "gemini-3.5-flash-lite";
const MAX_HOSTED_IMAGES = 3;
const MAX_PROMPT_CHARS = 120_000;
const MAX_SYSTEM_INSTRUCTION_CHARS = 32_000;
const MAX_AUDIO_BYTES = 12 * 1024 * 1024;
const MAX_AUDIO_BASE64_CHARS = Math.ceil((MAX_AUDIO_BYTES / 3) * 4);
const MAX_IMAGE_BASE64_CHARS = 8 * 1024 * 1024;
const MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024;
const MAX_GEMINI_PASSTHROUGH_BYTES = 8 * 1024 * 1024;
const UPSTREAM_TIMEOUT_MS = 30_000;
const MAX_TRANSCRIBE_LANGUAGE_CHARS = 16;
/** Longest clip one `/transcribe` action may send to Deepgram (billed per minute). */
export const MAX_TRANSCRIBE_AUDIO_SECONDS = 120;
/** Output cap applied to every hosted Gemini call that does not set a lower one. */
const HOSTED_MAX_OUTPUT_TOKENS = GEMINI_REQUEST_LIMITS.maxOutputTokens;
const ALLOWED_GENERATE_RESPONSE_MIME_TYPES = new Set(["application/json", "text/plain"]);

/**
 * The subscriber id is the caller's only credential, so it must be
 * unguessable. RevenueCat anonymous ids (`$RCAnonymousID:` + a random UUIDv4,
 * 122 random bits) are the only ids the iOS and Android apps use (neither
 * calls `Purchases.logIn`; both SDKs emit the same 32-hex shape), and they are
 * the only shape accepted here. Custom ids (emails, usernames, …)
 * would be guessable and must not be enabled until requests carry a
 * device-bound proof of identity (App Attest) — see services/hosted-ai/README.md.
 */
const RC_ANONYMOUS_ID_PATTERN = /^\$RCAnonymousID:[0-9a-f]{32}$/;

/** Every upstream round-trip costs one metered action. */
const ACTION_COST = 1;

/**
 * Backoff between refund attempts when D1 rejects the compensating write.
 * The first attempt is inline; later ones run in `waitUntil` so the caller's
 * error response is not delayed.
 */
const REFUND_RETRY_DELAYS_MS: readonly number[] = [100, 400, 1500];

type HostedAIEnv = Pick<
  Env,
  | "GEMINI_API_KEY"
  | "DEEPGRAM_API_KEY"
  | "REVENUECAT_API_KEY"
  | "HOSTED_AI_LIVE_MODEL"
  | "HOSTED_AI_TRANSCRIBE_MODEL"
  | "CHALLENGE_DB"
  | "HOSTED_AI_USER_RATE_LIMITER"
  | "HOSTED_AI_ADDRESS_RATE_LIMITER"
>;

/** Injection points for tests; production uses the real bindings. */
export interface HostedAIDependencies {
  fetch?: typeof fetch;
  store?: LedgerStore;
  now?: () => Date;
  /**
   * `ExecutionContext.waitUntil`; lets refund retries outlive the response and
   * keeps a voice block charge alive if the app disconnects mid-write.
   */
  waitUntil?: (promise: Promise<unknown>) => void;
  sleep?: (ms: number) => Promise<void>;
  /** Workers-only WebSocket APIs used by the voice relay. */
  live?: LiveRuntime;
}

export interface LiveRuntime {
  webSocketPair?: () => { client: WebSocket; server: WebSocket };
  /** Builds the `101 Switching Protocols` response handing `client` to the app. */
  upgradeResponse?: (client: WebSocket) => Response;
  /** Schedules `callback`; returns a function that cancels it. */
  setTimer?: (callback: () => void, ms: number) => () => void;
}

class HostedAIError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly extra?: Record<string, unknown>,
    readonly headers?: Record<string, string>
  ) {
    super(code);
    this.name = "HostedAIError";
  }
}

/**
 * `detail` must already be safe to log: a short machine token or the output of
 * `summarizeUpstreamErrorBody`, never a raw provider body.
 */
class UpstreamError extends Error {
  constructor(
    readonly provider: "gemini" | "deepgram",
    readonly upstreamStatus: number,
    detail: string
  ) {
    super(detail);
    this.name = "UpstreamError";
  }
}

interface HostedClientContext {
  userId: string;
  userIdHash: string;
}

/**
 * A fully validated request whose only remaining work is the upstream call.
 * Handlers return this so validation runs before the ledger is charged.
 */
interface PreparedRequest {
  execute: (fetchImpl: typeof fetch) => Promise<Response>;
}

type RoutePreparer = (request: Request, env: HostedAIEnv) => Promise<PreparedRequest>;

export async function handleHostedAIRequest(
  request: Request,
  env: HostedAIEnv,
  deps: HostedAIDependencies = {}
): Promise<Response> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith(HOSTED_AI_API_PREFIX)) {
    return json({ error: "not_found" }, 404);
  }
  const subpath = url.pathname.slice(HOSTED_AI_API_PREFIX.length) || "/";
  const fetchImpl = deps.fetch ?? fetch;
  const now = deps.now ?? (() => new Date());

  let client: HostedClientContext | null = null;
  try {
    if (subpath === "/quota" || subpath === "/live") {
      if (request.method !== "GET") throw new HostedAIError(405, "method_not_allowed", undefined, { Allow: "GET" });
    } else if (request.method !== "POST") {
      throw new HostedAIError(405, "method_not_allowed", undefined, { Allow: "POST" });
    }
    if (subpath === "/live" && request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      throw new HostedAIError(426, "upgrade_required", undefined, { Upgrade: "websocket" });
    }

    client = await identifyClient(request, env);
    const ledger = buildLedgerContext(env, deps, fetchImpl, now);

    if (subpath === "/quota") {
      const refresh = url.searchParams.get("refresh") === "1";
      const row = await loadVerifiedLedger(ledger, client.userId, client.userIdHash, refresh);
      const at = now();
      const quota = quotaFromRow(row, at);
      return json({ quota, voice: voiceQuotaFromRow(row, at) }, 200, quotaHeaders(quota));
    }

    if (subpath === "/live") {
      return await openLiveSession(request, env, deps, client, ledger, fetchImpl);
    }

    let prepare: RoutePreparer;
    if (subpath === "/generate") prepare = prepareGenerate;
    else if (subpath === "/gemini") prepare = prepareGeminiPassthrough;
    else if (subpath === "/transcribe") prepare = prepareTranscribe;
    else throw new HostedAIError(404, "not_found");

    // Entitlement first: an unverified id never gets its body parsed.
    const row = await loadVerifiedLedger(ledger, client.userId, client.userIdHash);
    if (row.plan === "none") {
      throw new HostedAIError(403, "subscription_required", { quota: quotaFromRow(row, now()) });
    }

    // Provider configuration and body validation complete before the ledger
    // is touched, so 4xx/503 validation failures never depend on a refund.
    const prepared = await prepare(request, env);

    const spend = await spendFromLedger(ledger, client.userIdHash, row, ACTION_COST);
    if (spend.status === "quota_exceeded") {
      throw new HostedAIError(402, "quota_exceeded", { quota: spend.quota }, quotaHeaders(spend.quota));
    }

    try {
      const response = await prepared.execute(fetchImpl);
      for (const [key, value] of Object.entries(quotaHeaders(spend.quota))) {
        response.headers.set(key, value);
      }
      return response;
    } catch (error) {
      if (isUnbilledUpstreamFailure(error)) {
        await refundSafely(ledger, client.userIdHash, spend.receipt, deps);
      }
      throw error;
    }
  } catch (error) {
    return errorResponse(error, client, env);
  }
}

/**
 * Only failures the provider does not bill are refunded: an HTTP error status
 * from Gemini/Deepgram, or a request that never reached it. A timeout (the
 * provider keeps generating after the Worker aborts) or a 200 with no usable
 * output (safety block, output exhausted) is still billed upstream, so it keeps
 * its action — otherwise those calls would be free and unmetered.
 */
function isUnbilledUpstreamFailure(error: unknown): boolean {
  if (error instanceof UpstreamError) return error.upstreamStatus !== 200;
  const name = error instanceof Error ? error.name : "";
  return name !== "TimeoutError" && name !== "AbortError";
}

function buildLedgerContext(
  env: HostedAIEnv,
  deps: HostedAIDependencies,
  fetchImpl: typeof fetch,
  now: () => Date
): LedgerContext {
  const store = deps.store ?? new D1LedgerStore(env.CHALLENGE_DB.withSession("first-primary"));
  return {
    store,
    now,
    verify: async (userId) => {
      const apiKey = env.REVENUECAT_API_KEY;
      if (!apiKey) throw new HostedAIError(503, "entitlements_not_configured");
      return await fetchRevenueCatEntitlement(userId, apiKey, fetchImpl, now());
    },
    log: (event) => console.error(JSON.stringify(event)),
  };
}

async function identifyClient(request: Request, env: HostedAIEnv): Promise<HostedClientContext> {
  const userId = request.headers.get("X-Fud-User-Id")?.trim() ?? "";
  if (!RC_ANONYMOUS_ID_PATTERN.test(userId)) {
    throw new HostedAIError(401, "invalid_user_id");
  }
  const userIdHash = await sha256Hex(userId);

  const address = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const [userLimit, addressLimit] = await Promise.all([
    env.HOSTED_AI_USER_RATE_LIMITER.limit({ key: `user:${userIdHash}` }),
    env.HOSTED_AI_ADDRESS_RATE_LIMITER.limit({ key: `address:${await sha256Hex(address)}` }),
  ]);
  if (!userLimit.success || !addressLimit.success) {
    throw new HostedAIError(429, "rate_limited", undefined, { "Retry-After": "60" });
  }

  return { userId, userIdHash };
}

// MARK: Voice relay (WebSocket)

/**
 * Runs every check before the upgrade, so a refused session is a plain JSON
 * response and costs nothing: verified plan (403), relay configuration (503),
 * at least one payable voice block (402), and a working upstream socket (503).
 * Charging starts at `setupComplete` (see hosted-ai-live.ts).
 */
async function openLiveSession(
  request: Request,
  env: HostedAIEnv,
  deps: HostedAIDependencies,
  client: HostedClientContext,
  ledger: LedgerContext,
  fetchImpl: typeof fetch
): Promise<Response> {
  const row = await loadVerifiedLedger(ledger, client.userId, client.userIdHash);
  const at = ledger.now();
  const quota = quotaFromRow(row, at);
  const voice = voiceQuotaFromRow(row, at);
  if (row.plan === "none") throw new HostedAIError(403, "subscription_required", { quota, voice });

  const apiKey = env.GEMINI_API_KEY;
  const model = resolveLiveModel(env.HOSTED_AI_LIVE_MODEL);
  if (!apiKey || !model) {
    if (apiKey) console.error(JSON.stringify({ event: "hosted_ai_live_misconfigured", setting: "HOSTED_AI_LIVE_MODEL" }));
    throw new HostedAIError(503, "voice_unavailable");
  }
  if (voiceBlocksRemaining(voice) < 1) throw new HostedAIError(402, "voice_quota_exceeded", { quota, voice });

  const runtime = deps.live ?? {};
  const setTimer = runtime.setTimer ?? setRuntimeTimer;
  const upstream = await connectGeminiLive(apiKey, fetchImpl, setTimer, client, env);
  try {
    const pair = (runtime.webSocketPair ?? createWebSocketPair)();
    startLiveRelay({
      client: pair.server,
      upstream,
      model,
      ledger,
      userIdHash: client.userIdHash,
      row,
      clock: () => ledger.now().getTime(),
      setTimer,
      sleep: deps.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))),
      ...(deps.waitUntil ? { waitUntil: deps.waitUntil } : {}),
      log: (event) => console.error(JSON.stringify(event)),
      redact: (text) => redactSecrets(text, env),
      platform: clientPlatform(request),
      resumption: createLiveResumptionCodec(apiKey, client.userIdHash, () => ledger.now().getTime()),
    });
    return (runtime.upgradeResponse ?? webSocketUpgradeResponse)(pair.client);
  } catch (error) {
    try {
      upstream.close(1011, "relay_failed");
    } catch {
      // Not accepted yet; dropped with the invocation.
    }
    throw error;
  }
}

/**
 * Opens the upstream Gemini Live socket with the key in a header. The timeout
 * covers the handshake only: it is cancelled once the response arrives, so it
 * can never tear down an established socket. Failures are logged with
 * structured fields only and surface as 503 `voice_unavailable`.
 */
async function connectGeminiLive(
  apiKey: string,
  fetchImpl: typeof fetch,
  setTimer: (callback: () => void, ms: number) => () => void,
  client: HostedClientContext,
  env: HostedAIEnv
): Promise<WebSocket> {
  const controller = new AbortController();
  const cancelTimeout = setTimer(() => controller.abort(), LIVE_CONNECT_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetchImpl(GEMINI_LIVE_URL, {
      headers: { Upgrade: "websocket", "x-goog-api-key": apiKey },
      signal: controller.signal,
    });
  } catch (error) {
    const detail = controller.signal.aborted
      ? "connect_timeout"
      : `connect_failed:${error instanceof Error ? error.name : typeof error}`;
    throw liveConnectFailure(0, detail, true, client, env);
  } finally {
    cancelTimeout();
  }

  const socket = response.webSocket;
  if (!socket) {
    const raw = await response.text().catch(() => "");
    const transient = response.status === 429 || response.status >= 500;
    throw liveConnectFailure(response.status, summarizeUpstreamErrorBody(raw), transient, client, env);
  }
  return socket;
}

function liveConnectFailure(
  upstreamStatus: number,
  detail: string,
  transient: boolean,
  client: HostedClientContext,
  env: HostedAIEnv
): HostedAIError {
  console.error(
    JSON.stringify({
      event: "hosted_ai_live_connect_failed",
      upstreamStatus,
      detail: redactSecrets(detail.slice(0, 200), env),
      userIdHash: client.userIdHash.slice(0, 12),
    })
  );
  return new HostedAIError(503, "voice_unavailable", undefined, transient ? { "Retry-After": "30" } : undefined);
}

/** `X-Fud-Platform` (ios|android); informational, used in relay logs only. */
function clientPlatform(request: Request): string {
  const value = request.headers.get("X-Fud-Platform")?.trim().toLowerCase();
  if (!value) return "unknown";
  return value === "ios" || value === "android" ? value : "other";
}

/**
 * Reverses a committed spend after the upstream call failed. The first attempt
 * runs inline; if D1 rejects it, retries continue in `waitUntil` with backoff
 * so a transient error does not silently consume the subscriber's quota. If
 * every attempt fails the full receipt is logged (with the complete user hash)
 * so the ledger can be reconciled by hand. Refunds are keyed on the receipt id
 * (see `LedgerStore.refund`), so retrying after a write that actually
 * committed is a no-op rather than a second credit.
 */
async function refundSafely(
  ledger: LedgerContext,
  userIdHash: string,
  receipt: SpendReceipt,
  deps: HostedAIDependencies
): Promise<void> {
  if (receipt.fromDaily === 0 && receipt.fromCredits === 0) return;
  const firstError = await attemptRefund(ledger, userIdHash, receipt);
  if (firstError === null) return;

  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const retries = (async () => {
    let lastError: unknown = firstError;
    for (const [index, delay] of REFUND_RETRY_DELAYS_MS.entries()) {
      await sleep(delay);
      lastError = await attemptRefund(ledger, userIdHash, receipt);
      if (lastError === null) {
        console.error(JSON.stringify({ event: "hosted_ai_refund_recovered", attempt: index + 2, userIdHash }));
        return;
      }
    }
    console.error(
      JSON.stringify({
        event: "hosted_ai_refund_failed",
        attempts: REFUND_RETRY_DELAYS_MS.length + 1,
        errorType: lastError instanceof Error ? lastError.name : typeof lastError,
        userIdHash,
        receipt,
      })
    );
  })();

  if (deps.waitUntil) deps.waitUntil(retries);
  else await retries;
}

async function attemptRefund(ledger: LedgerContext, userIdHash: string, receipt: SpendReceipt): Promise<unknown> {
  try {
    await refundToLedger(ledger, userIdHash, receipt);
    return null;
  } catch (error) {
    return error ?? new Error("unknown_refund_error");
  }
}

function errorResponse(error: unknown, client: HostedClientContext | null, env: HostedAIEnv): Response {
  if (error instanceof HostedAIError) {
    return json({ error: error.code, ...error.extra }, error.status, error.headers);
  }

  const userIdHash = client?.userIdHash.slice(0, 12);
  if (error instanceof UpstreamError) {
    console.error(
      JSON.stringify({
        event: "hosted_ai_upstream_error",
        provider: error.provider,
        upstreamStatus: error.upstreamStatus,
        detail: redactSecrets(error.message.slice(0, 200), env),
        userIdHash,
      })
    );
    if (error.upstreamStatus === 429 || error.upstreamStatus >= 500) {
      return json({ error: "upstream_unavailable" }, 503, { "Retry-After": "30" });
    }
    return json({ error: "upstream_error" }, 502);
  }

  const name = error instanceof Error ? error.name : typeof error;
  const message = error instanceof Error ? error.message : String(error);
  console.error(
    JSON.stringify({
      event: "hosted_ai_error",
      errorType: name,
      message: redactSecrets(message.slice(0, 500), env),
      userIdHash,
    })
  );
  if (name === "TimeoutError" || name === "AbortError") {
    return json({ error: "upstream_timeout" }, 504);
  }
  if (message.startsWith("revenuecat_") || name === "EntitlementLookupError") {
    return json({ error: "entitlement_unavailable" }, 503, { "Retry-After": "30" });
  }
  return json({ error: "internal_error" }, 500);
}

function quotaHeaders(quota: QuotaSnapshot): Record<string, string> {
  return {
    "X-Fud-Quota-Plan": quota.plan,
    "X-Fud-Quota-Day": quota.day,
    "X-Fud-Quota-Daily-Used": String(quota.dailyUsed),
    "X-Fud-Quota-Daily-Limit": String(quota.dailyLimit),
    "X-Fud-Quota-Credits": String(quota.creditBank),
  };
}

// MARK: Route handlers

async function prepareGenerate(request: Request, env: HostedAIEnv): Promise<PreparedRequest> {
  const geminiKey = env.GEMINI_API_KEY;
  if (!geminiKey) throw new HostedAIError(503, "gemini_not_configured");

  const body = await readJsonObjectBody(request, MAX_REQUEST_BODY_BYTES);

  const prompt = typeof body.prompt === "string" ? body.prompt.trim() : "";
  if (!prompt) throw new HostedAIError(400, "missing_prompt");
  if (prompt.length > MAX_PROMPT_CHARS) throw new HostedAIError(400, "prompt_too_long");

  const systemInstruction = typeof body.systemInstruction === "string" ? body.systemInstruction.trim() : "";
  if (systemInstruction.length > MAX_SYSTEM_INSTRUCTION_CHARS) {
    throw new HostedAIError(400, "system_instruction_too_long");
  }

  const rawImages = Array.isArray(body.images) ? body.images : [];
  if (rawImages.length > MAX_HOSTED_IMAGES) throw new HostedAIError(400, "too_many_images");
  for (const image of rawImages) {
    if (typeof image !== "string" || image.length > MAX_IMAGE_BASE64_CHARS) {
      throw new HostedAIError(400, "image_too_large");
    }
  }

  const parts: Array<Record<string, unknown>> = (rawImages as string[]).map((data) => ({
    inlineData: { mimeType: "image/jpeg", data },
  }));
  parts.push({ text: prompt });

  const generationConfig: Record<string, unknown> = { maxOutputTokens: HOSTED_MAX_OUTPUT_TOKENS };
  if (body.responseMimeType !== undefined) {
    if (typeof body.responseMimeType !== "string" || !ALLOWED_GENERATE_RESPONSE_MIME_TYPES.has(body.responseMimeType)) {
      throw new HostedAIError(400, "unsupported_response_mime_type");
    }
    generationConfig.responseMimeType = body.responseMimeType;
  }

  const geminiBody: Record<string, unknown> = { contents: [{ parts }], generationConfig };
  if (systemInstruction) {
    geminiBody.systemInstruction = { parts: [{ text: systemInstruction }] };
  }

  return {
    execute: async (fetchImpl) => {
      const upstream = await fetchGemini(geminiKey, geminiBody, fetchImpl);
      const text = extractGeminiText(upstream);
      if (!text) throw new UpstreamError("gemini", 200, "invalid_upstream_response");
      return json({ text });
    },
  };
}

async function prepareGeminiPassthrough(request: Request, env: HostedAIEnv): Promise<PreparedRequest> {
  const geminiKey = env.GEMINI_API_KEY;
  if (!geminiKey) throw new HostedAIError(503, "gemini_not_configured");

  const body = await readJsonObjectBody(request, MAX_GEMINI_PASSTHROUGH_BYTES);
  if (body.requestBody === undefined) {
    throw new HostedAIError(400, "missing_request_body");
  }

  const sanitized = sanitizeGeminiRequestBody(body.requestBody);
  if (!sanitized.ok) {
    throw new HostedAIError(400, "invalid_request_body", { detail: sanitized.error });
  }
  const config = (sanitized.body.generationConfig as Record<string, unknown> | undefined) ?? {};
  if (config.maxOutputTokens === undefined) {
    sanitized.body.generationConfig = { ...config, maxOutputTokens: HOSTED_MAX_OUTPUT_TOKENS };
  }

  return {
    execute: async (fetchImpl) => json(await fetchGemini(geminiKey, sanitized.body, fetchImpl)),
  };
}

async function prepareTranscribe(request: Request, env: HostedAIEnv): Promise<PreparedRequest> {
  const deepgramKey = env.DEEPGRAM_API_KEY;
  if (!deepgramKey) throw new HostedAIError(503, "deepgram_not_configured");

  const body = await readJsonObjectBody(request, MAX_REQUEST_BODY_BYTES);
  if (typeof body.audio !== "string" || !body.audio) throw new HostedAIError(400, "missing_audio");
  if (body.audio.length > MAX_AUDIO_BASE64_CHARS) throw new HostedAIError(400, "audio_too_large");

  let audioBytes: Uint8Array;
  try {
    audioBytes = decodeBase64(body.audio);
  } catch {
    throw new HostedAIError(400, "invalid_audio_encoding");
  }
  if (audioBytes.byteLength > MAX_AUDIO_BYTES) throw new HostedAIError(400, "audio_too_large");

  const mimeType = typeof body.mimeType === "string" && body.mimeType.trim() ? body.mimeType.trim() : "audio/wav";
  const container = audioContainerForMimeType(mimeType);
  if (!container) throw new HostedAIError(400, "unsupported_audio_type");

  const language = typeof body.language === "string" ? body.language.trim() : "";
  if (language && !/^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(language)) {
    throw new HostedAIError(400, "invalid_language");
  }
  if (language.length > MAX_TRANSCRIBE_LANGUAGE_CHARS) throw new HostedAIError(400, "invalid_language");

  // Deepgram bills per minute; bound the measured duration, not just the bytes.
  const seconds = measureAudioDurationSeconds(audioBytes, container);
  if (seconds === null) throw new HostedAIError(400, "unreadable_audio");
  if (seconds > MAX_TRANSCRIBE_AUDIO_SECONDS) {
    throw new HostedAIError(400, "audio_too_long", { maxSeconds: MAX_TRANSCRIBE_AUDIO_SECONDS });
  }

  const model = env.HOSTED_AI_TRANSCRIBE_MODEL ?? "nova-2";
  if (model !== "nova-2" && model !== "nova-3") {
    throw new HostedAIError(503, "deepgram_not_configured");
  }
  const params = new URLSearchParams({ model, smart_format: "true" });
  if (language) params.set("language", language);
  else params.set("detect_language", "true");

  return {
    execute: async (fetchImpl) => {
      const upstream = await fetchImpl(`https://api.deepgram.com/v1/listen?${params}`, {
        method: "POST",
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
        headers: {
          Authorization: `Token ${deepgramKey}`,
          "Content-Type": mimeType,
        },
        body: audioBytes,
      });

      const payload = await readUpstreamJson(upstream, "deepgram");
      const transcript = (
        (payload.results as Record<string, unknown> | undefined)?.channels as
          | Array<Record<string, unknown>>
          | undefined
      )?.[0]?.alternatives as Array<Record<string, unknown>> | undefined;
      const text = (transcript?.[0]?.transcript as string | undefined)?.trim() ?? "";
      return json({ text });
    },
  };
}

// MARK: Upstream helpers

async function fetchGemini(
  apiKey: string,
  body: Record<string, unknown>,
  fetchImpl: typeof fetch
): Promise<Record<string, unknown>> {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${HOSTED_GEMINI_MODEL}:generateContent`;
  const response = await fetchImpl(url, {
    method: "POST",
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    headers: {
      "Content-Type": "application/json",
      "X-goog-api-key": apiKey,
    },
    body: JSON.stringify(body),
  });
  return await readUpstreamJson(response, "gemini");
}

async function readUpstreamJson(
  response: Response,
  provider: "gemini" | "deepgram"
): Promise<Record<string, unknown>> {
  const raw = await response.text();
  if (!response.ok) {
    throw new UpstreamError(provider, response.status, summarizeUpstreamErrorBody(raw));
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== "object") throw new Error("not_an_object");
    return parsed as Record<string, unknown>;
  } catch {
    throw new UpstreamError(provider, response.status, "invalid_upstream_json");
  }
}

/**
 * Reduces a provider error body to structured identifiers only. Free-text
 * fields (`message`, `err_msg`, …) are dropped on purpose: Gemini and Deepgram
 * echo request details, and Deepgram includes the presented token, in them.
 */
export function summarizeUpstreamErrorBody(raw: string): string {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return `non_json_body bytes=${raw.length}`;
  }
  if (!isPlainObject(parsed)) return `non_object_body bytes=${raw.length}`;

  const error = isPlainObject(parsed.error) ? parsed.error : parsed;
  const token = (value: unknown): string | null => {
    if (typeof value === "number" && Number.isFinite(value)) return String(value);
    if (typeof value !== "string") return null;
    const cleaned = value.replace(/[^A-Za-z0-9_.:-]/g, "").slice(0, 64);
    return cleaned || null;
  };
  const fields: string[] = [];
  for (const key of ["status", "code", "err_code", "request_id"] as const) {
    const value = token(error[key]);
    if (value !== null) fields.push(`${key}=${value}`);
  }
  return fields.length > 0 ? fields.join(" ") : `unrecognized_body bytes=${raw.length}`;
}

/** Strips any configured provider secret out of text destined for the logs. */
function redactSecrets(text: string, env: HostedAIEnv): string {
  let result = text;
  for (const secret of [env.GEMINI_API_KEY, env.DEEPGRAM_API_KEY, env.REVENUECAT_API_KEY]) {
    if (typeof secret === "string" && secret.length >= 8) {
      result = result.split(secret).join("[redacted]");
    }
  }
  return result;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Parses a JSON request body and requires it to be a non-null, non-array object. */
async function readJsonObjectBody(request: Request, maxBytes: number): Promise<Record<string, unknown>> {
  const contentLength = request.headers.get("Content-Length");
  if (contentLength && Number(contentLength) > maxBytes) {
    throw new HostedAIError(413, "body_too_large");
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength > maxBytes) {
    throw new HostedAIError(413, "body_too_large");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(buffer));
  } catch {
    throw new HostedAIError(400, "invalid_json");
  }
  if (!isPlainObject(parsed)) throw new HostedAIError(400, "invalid_body");
  return parsed;
}

function extractGeminiText(payload: Record<string, unknown>): string | null {
  const candidates = payload.candidates as Array<Record<string, unknown>> | undefined;
  const content = candidates?.[0]?.content as Record<string, unknown> | undefined;
  const parts = content?.parts as Array<Record<string, unknown>> | undefined;
  const text = parts
    ?.map((part) => (typeof part.text === "string" ? part.text : ""))
    .join("");
  return text?.trim() ? text : null;
}

function decodeBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...headers,
    },
  });
}
