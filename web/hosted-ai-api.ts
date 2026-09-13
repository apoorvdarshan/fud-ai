/**
 * Fud AI hosted path — proxies Gemini Flash-Lite + Deepgram STT for Plus/Pro
 * subscribers.
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
 */

import { sanitizeGeminiRequestBody } from "./hosted-ai-gemini-request";
import {
  D1LedgerStore,
  fetchRevenueCatEntitlement,
  loadVerifiedLedger,
  quotaFromRow,
  refundToLedger,
  sha256Hex,
  spendFromLedger,
  type LedgerContext,
  type LedgerStore,
  type QuotaSnapshot,
  type SpendReceipt,
} from "./hosted-ai-ledger";

export const HOSTED_AI_API_PREFIX = "/api/hosted-ai/v1";

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

const ALLOWED_AUDIO_MIME_TYPES = new Set([
  "audio/wav",
  "audio/x-wav",
  "audio/wave",
  "audio/mp4",
  "audio/m4a",
  "audio/x-m4a",
  "audio/aac",
  "audio/mpeg",
  "audio/webm",
  "audio/ogg",
  "audio/flac",
]);

/**
 * The subscriber id is the caller's only credential, so it must be
 * unguessable. RevenueCat anonymous ids (`$RCAnonymousID:` + 128 random bits)
 * are the only ids the iOS app uses (it never calls `Purchases.logIn`), and
 * they are the only shape accepted here. Custom ids (emails, usernames, …)
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
  | "CHALLENGE_DB"
  | "HOSTED_AI_USER_RATE_LIMITER"
  | "HOSTED_AI_ADDRESS_RATE_LIMITER"
>;

/** Injection points for tests; production uses the real bindings. */
export interface HostedAIDependencies {
  fetch?: typeof fetch;
  store?: LedgerStore;
  now?: () => Date;
  /** `ExecutionContext.waitUntil`; lets refund retries outlive the response. */
  waitUntil?: (promise: Promise<unknown>) => void;
  sleep?: (ms: number) => Promise<void>;
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
    if (subpath === "/quota") {
      if (request.method !== "GET") throw new HostedAIError(405, "method_not_allowed", undefined, { Allow: "GET" });
    } else if (request.method !== "POST") {
      throw new HostedAIError(405, "method_not_allowed", undefined, { Allow: "POST" });
    }

    client = await identifyClient(request, env);
    const ledger = buildLedgerContext(env, deps, fetchImpl, now);

    if (subpath === "/quota") {
      const refresh = url.searchParams.get("refresh") === "1";
      const row = await loadVerifiedLedger(ledger, client.userId, client.userIdHash, refresh);
      return json({ quota: quotaFromRow(row, now()) }, 200, quotaHeaders(quotaFromRow(row, now())));
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
      await refundSafely(ledger, client.userIdHash, spend.receipt, deps);
      throw error;
    }
  } catch (error) {
    return errorResponse(error, client, env);
  }
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

  const geminiBody: Record<string, unknown> = { contents: [{ parts }] };
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
  if (!ALLOWED_AUDIO_MIME_TYPES.has(mimeType)) throw new HostedAIError(400, "unsupported_audio_type");

  const language = typeof body.language === "string" ? body.language.trim() : "";
  if (language && !/^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(language)) {
    throw new HostedAIError(400, "invalid_language");
  }
  if (language.length > MAX_TRANSCRIBE_LANGUAGE_CHARS) throw new HostedAIError(400, "invalid_language");

  const params = new URLSearchParams({ model: "nova-2", smart_format: "true" });
  if (language) params.set("language", language);

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
