/**
 * Fud AI hosted path — proxies Gemini Flash-Lite + Deepgram STT.
 * Secrets live in Worker env vars only; mobile apps send a shared app secret
 * plus RevenueCat user id and plan for v1 client-side entitlement gating.
 *
 * TODO: server-side meter enforcement via RevenueCat webhooks + D1/KV ledger.
 * The worker currently validates the shared secret, user id, and plus/pro plan
 * but does not yet verify RevenueCat entitlements server-side.
 */

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

type HostedAIEnv = Pick<
  Env,
  "GEMINI_API_KEY" | "DEEPGRAM_API_KEY" | "FUD_HOSTED_AI_APP_SECRET"
>;

type HostedClientContext = {
  userId: string;
  plan: "plus" | "pro";
};

export async function handleHostedAIRequest(
  request: Request,
  env: HostedAIEnv
): Promise<Response> {
  const url = new URL(request.url);
  if (!url.pathname.startsWith(HOSTED_AI_API_PREFIX)) {
    return json({ error: "not_found" }, 404);
  }

  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  const authResult = validateHostedAuth(request, env);
  if (authResult instanceof Response) {
    return authResult;
  }

  const subpath = url.pathname.slice(HOSTED_AI_API_PREFIX.length) || "/";
  try {
    if (subpath === "/generate") {
      return await handleGenerate(request, env);
    }
    if (subpath === "/gemini") {
      return await handleGeminiPassthrough(request, env);
    }
    if (subpath === "/transcribe") {
      return await handleTranscribe(request, env);
    }
    return json({ error: "not_found" }, 404);
  } catch (err) {
    const message = err instanceof Error ? err.message : "internal_error";
    console.error(
      JSON.stringify({ event: "hosted_ai_error", userId: authResult.userId, plan: authResult.plan, message })
    );
    if (message === "body_too_large") {
      return json({ error: message }, 413);
    }
    return json({ error: message }, 502);
  }
}

function validateHostedAuth(request: Request, env: HostedAIEnv): Response | HostedClientContext {
  const auth = request.headers.get("Authorization") ?? "";
  const secret = env.FUD_HOSTED_AI_APP_SECRET;
  if (!secret || auth !== `Bearer ${secret}`) {
    return json({ error: "unauthorized" }, 401);
  }

  const userId = request.headers.get("X-Fud-User-Id")?.trim() ?? "";
  const plan = request.headers.get("X-Fud-Plan")?.trim() ?? "";
  if (!userId || userId.length > 128) {
    return json({ error: "invalid_user_id" }, 401);
  }
  if (plan !== "plus" && plan !== "pro") {
    return json({ error: "invalid_plan" }, 403);
  }

  return { userId, plan };
}

async function handleGenerate(request: Request, env: HostedAIEnv): Promise<Response> {
  const geminiKey = env.GEMINI_API_KEY;
  if (!geminiKey) return json({ error: "gemini_not_configured" }, 503);

  const body = (await readJsonBody(request, MAX_REQUEST_BODY_BYTES)) as {
    prompt?: string;
    images?: string[];
    systemInstruction?: string;
  };

  const prompt = body.prompt?.trim();
  if (!prompt) return json({ error: "missing_prompt" }, 400);
  if (prompt.length > MAX_PROMPT_CHARS) return json({ error: "prompt_too_long" }, 400);

  const systemInstruction = body.systemInstruction?.trim();
  if (systemInstruction && systemInstruction.length > MAX_SYSTEM_INSTRUCTION_CHARS) {
    return json({ error: "system_instruction_too_long" }, 400);
  }

  const rawImages = Array.isArray(body.images) ? body.images.slice(0, MAX_HOSTED_IMAGES) : [];
  for (const image of rawImages) {
    if (typeof image !== "string" || image.length > MAX_IMAGE_BASE64_CHARS) {
      return json({ error: "image_too_large" }, 400);
    }
  }

  const parts: Array<Record<string, unknown>> = rawImages.map((data) => ({
    inlineData: { mimeType: "image/jpeg", data },
  }));
  parts.push({ text: prompt });

  const geminiBody: Record<string, unknown> = {
    contents: [{ parts }],
  };
  if (systemInstruction) {
    geminiBody.systemInstruction = {
      parts: [{ text: systemInstruction }],
    };
  }

  const upstream = await fetchGemini(geminiKey, geminiBody);
  const text = extractGeminiText(upstream);
  if (!text) return json({ error: "invalid_upstream_response" }, 502);
  return json({ text });
}

async function handleGeminiPassthrough(
  request: Request,
  env: HostedAIEnv
): Promise<Response> {
  const geminiKey = env.GEMINI_API_KEY;
  if (!geminiKey) return json({ error: "gemini_not_configured" }, 503);

  const body = (await readJsonBody(request, MAX_GEMINI_PASSTHROUGH_BYTES)) as {
    requestBody?: Record<string, unknown>;
  };
  if (!body.requestBody || typeof body.requestBody !== "object") {
    return json({ error: "missing_request_body" }, 400);
  }

  const upstream = await fetchGemini(geminiKey, body.requestBody);
  return new Response(JSON.stringify(upstream), {
    status: 200,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

async function handleTranscribe(request: Request, env: HostedAIEnv): Promise<Response> {
  const deepgramKey = env.DEEPGRAM_API_KEY;
  if (!deepgramKey) return json({ error: "deepgram_not_configured" }, 503);

  const body = (await readJsonBody(request, MAX_REQUEST_BODY_BYTES)) as {
    audio?: string;
    mimeType?: string;
    language?: string;
  };
  if (!body.audio) return json({ error: "missing_audio" }, 400);
  if (body.audio.length > MAX_AUDIO_BASE64_CHARS) {
    return json({ error: "audio_too_large" }, 400);
  }

  const audioBytes = decodeBase64(body.audio);
  if (audioBytes.byteLength > MAX_AUDIO_BYTES) {
    return json({ error: "audio_too_large" }, 400);
  }

  const mimeType = body.mimeType?.trim() || "audio/wav";
  const language = body.language?.trim();

  const params = new URLSearchParams({
    model: "nova-2",
    smart_format: "true",
  });
  if (language) params.set("language", language);

  const upstream = await fetch(`https://api.deepgram.com/v1/listen?${params}`, {
    method: "POST",
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    headers: {
      Authorization: `Token ${deepgramKey}`,
      "Content-Type": mimeType,
    },
    body: audioBytes,
  });

  const payload = (await upstream.json()) as Record<string, unknown>;
  if (!upstream.ok) {
    const detail =
      (payload.err_msg as string | undefined) ??
      (payload.error as string | undefined) ??
      "deepgram_error";
    return json({ error: detail }, upstream.status);
  }

  const transcript =
    ((payload.results as Record<string, unknown> | undefined)?.channels as
      | Array<Record<string, unknown>>
      | undefined)?.[0]?.alternatives as Array<Record<string, unknown>> | undefined;
  const text = (transcript?.[0]?.transcript as string | undefined)?.trim() ?? "";
  if (!text) return json({ error: "empty_transcript" }, 502);
  return json({ text });
}

async function fetchGemini(
  apiKey: string,
  body: Record<string, unknown>
): Promise<Record<string, unknown>> {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${HOSTED_GEMINI_MODEL}:generateContent`;
  const response = await fetch(url, {
    method: "POST",
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    headers: {
      "Content-Type": "application/json",
      "X-goog-api-key": apiKey,
    },
    body: JSON.stringify(body),
  });
  const payload = (await response.json()) as Record<string, unknown>;
  if (!response.ok) {
    const message =
      ((payload.error as Record<string, unknown> | undefined)?.message as string | undefined) ??
      "gemini_error";
    throw new Error(message);
  }
  return payload;
}

async function readJsonBody(request: Request, maxBytes: number): Promise<unknown> {
  const contentLength = request.headers.get("Content-Length");
  if (contentLength && Number(contentLength) > maxBytes) {
    throw new Error("body_too_large");
  }
  const buffer = await request.arrayBuffer();
  if (buffer.byteLength > maxBytes) {
    throw new Error("body_too_large");
  }
  return JSON.parse(new TextDecoder().decode(buffer));
}

function extractGeminiText(payload: Record<string, unknown>): string | null {
  const candidates = payload.candidates as Array<Record<string, unknown>> | undefined;
  const content = candidates?.[0]?.content as Record<string, unknown> | undefined;
  const parts = content?.parts as Array<Record<string, unknown>> | undefined;
  const text = parts?.[0]?.text as string | undefined;
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

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}
