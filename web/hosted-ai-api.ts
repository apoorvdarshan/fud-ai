/**
 * Fud AI hosted path — proxies Gemini Flash-Lite + Deepgram STT.
 * Secrets live in Worker env vars only; mobile apps send a shared app secret
 * plus the RevenueCat app user id for v1 client-side entitlement gating.
 *
 * TODO: server-side meter enforcement via RevenueCat webhooks + D1/KV ledger.
 */

export const HOSTED_AI_API_PREFIX = "/api/hosted-ai/v1";

const HOSTED_GEMINI_MODEL = "gemini-2.0-flash-lite";
const MAX_HOSTED_IMAGES = 3;
const MAX_PROMPT_CHARS = 120_000;
const MAX_AUDIO_BYTES = 12 * 1024 * 1024;

interface HostedAIEnv {
  GEMINI_API_KEY?: string;
  DEEPGRAM_API_KEY?: string;
  FUD_HOSTED_AI_APP_SECRET?: string;
}

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

  const auth = request.headers.get("Authorization") ?? "";
  const secret = env.FUD_HOSTED_AI_APP_SECRET;
  if (!secret || auth !== `Bearer ${secret}`) {
    return json({ error: "unauthorized" }, 401);
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
    console.error(JSON.stringify({ event: "hosted_ai_error", message }));
    return json({ error: message }, 502);
  }
}

async function handleGenerate(request: Request, env: HostedAIEnv): Promise<Response> {
  const geminiKey = env.GEMINI_API_KEY;
  if (!geminiKey) return json({ error: "gemini_not_configured" }, 503);

  const body = (await request.json()) as {
    prompt?: string;
    images?: string[];
    systemInstruction?: string;
  };

  const prompt = body.prompt?.trim();
  if (!prompt) return json({ error: "missing_prompt" }, 400);
  if (prompt.length > MAX_PROMPT_CHARS) return json({ error: "prompt_too_long" }, 400);

  const images = Array.isArray(body.images) ? body.images.slice(0, MAX_HOSTED_IMAGES) : [];
  const parts: Array<Record<string, unknown>> = images.map((data) => ({
    inlineData: { mimeType: "image/jpeg", data },
  }));
  parts.push({ text: prompt });

  const geminiBody: Record<string, unknown> = {
    contents: [{ parts }],
  };
  if (body.systemInstruction?.trim()) {
    geminiBody.systemInstruction = {
      parts: [{ text: body.systemInstruction.trim() }],
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

  const body = (await request.json()) as { requestBody?: Record<string, unknown> };
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

  const body = (await request.json()) as {
    audio?: string;
    mimeType?: string;
    language?: string;
  };
  if (!body.audio) return json({ error: "missing_audio" }, 400);

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
