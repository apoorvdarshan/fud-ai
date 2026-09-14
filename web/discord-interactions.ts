/**
 * Discord HTTP interactions for `/ask` (Cloudflare Worker).
 *
 * Slash commands are delivered to the Interactions Endpoint URL — no Discord
 * gateway / always-on VM. Mentions (@Fud AI) are not handled here.
 */

export const DISCORD_INTERACTIONS_PATH = "/api/discord/interactions";

const GEMINI_MODEL = "gemini-3.5-flash-lite";
const MAX_QUESTION_CHARS = 1_500;
const MAX_REPLY_CHARS = 1_800;

const SYSTEM_PROMPT = `You are the Fud AI Discord helper for the free, open-source calorie / fasting / workout tracker (iOS + Android).

Be friendly and concise (Discord-length answers).

Facts:
- No Fud AI account required; privacy-first / local-first.
- AI uses bring-your-own-key (BYOK) or optional hosted AI on iOS.
- Website: https://fud-ai.app
- Source / issues: https://github.com/apoorvdarshan/fud-ai
- Discord: https://discord.gg/Py4VrFctP3 — in that server use the /ask slash command for help from this bot

Not a doctor — no medical advice or diagnoses. If unsure about the app, point to GitHub issues or in-app Settings.`;

type DiscordEnv = {
  DISCORD_PUBLIC_KEY: string;
  DISCORD_APPLICATION_ID?: string;
  /** Free-tier Gemini key for Discord `/ask` only (never use hosted GEMINI_API_KEY). */
  DISCORD_GEMINI_API_KEY?: string;
};

type Interaction = {
  type: number;
  token: string;
  id: string;
  application_id?: string;
  data?: {
    name?: string;
    options?: Array<{ name?: string; type?: number; value?: unknown }>;
  };
  member?: { user?: { id?: string; username?: string } };
  user?: { id?: string; username?: string };
};

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim().toLowerCase();
  if (clean.length % 2 !== 0) throw new Error("invalid_hex");
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < clean.length; i += 2) {
    out[i / 2] = Number.parseInt(clean.slice(i, i + 2), 16);
  }
  return out;
}

/** Discord Ed25519 request verification (Web Crypto). */
export async function verifyDiscordSignature(
  body: string,
  signatureHex: string,
  timestamp: string,
  publicKeyHex: string,
): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey(
      "raw",
      hexToBytes(publicKeyHex),
      { name: "Ed25519" },
      false,
      ["verify"],
    );
    const message = new TextEncoder().encode(timestamp + body);
    return await crypto.subtle.verify(
      { name: "Ed25519" },
      key,
      hexToBytes(signatureHex),
      message,
    );
  } catch {
    return false;
  }
}

function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function optionString(interaction: Interaction, name: string): string {
  const options = interaction.data?.options ?? [];
  const hit = options.find((o) => o.name === name);
  return typeof hit?.value === "string" ? hit.value.trim() : "";
}

async function askGemini(apiKey: string, question: string): Promise<string> {
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/` +
    `${GEMINI_MODEL}:generateContent?key=${apiKey}`;

  const resp = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] },
      contents: [{ role: "user", parts: [{ text: question }] }],
      generationConfig: { temperature: 0.7, maxOutputTokens: 512 },
    }),
  });

  const data = (await resp.json()) as {
    error?: { message?: string };
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  };

  if (!resp.ok) {
    throw new Error(data.error?.message || `Gemini HTTP ${resp.status}`);
  }

  const text = (data.candidates?.[0]?.content?.parts ?? [])
    .map((p) => p.text ?? "")
    .join("")
    .trim();
  if (!text) throw new Error("empty_gemini_reply");
  return text.length > MAX_REPLY_CHARS ? `${text.slice(0, MAX_REPLY_CHARS - 1)}…` : text;
}

async function editInteractionReply(
  applicationId: string,
  interactionToken: string,
  content: string,
): Promise<void> {
  const url =
    `https://discord.com/api/v10/webhooks/${applicationId}/${interactionToken}/messages/@original`;
  const resp = await fetch(url, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ content }),
  });
  if (!resp.ok) {
    const detail = await resp.text();
    console.error("discord_followup_failed", resp.status, detail.slice(0, 200));
  }
}

async function fulfillAsk(
  env: DiscordEnv,
  applicationId: string,
  interactionToken: string,
  question: string,
): Promise<void> {
  // Discord must use its own free-tier key only — never GEMINI_API_KEY (hosted/billed).
  const apiKey = (env.DISCORD_GEMINI_API_KEY || "").trim();
  if (!apiKey) {
    await editInteractionReply(
      applicationId,
      interactionToken,
      "AI isn’t configured on the server yet. Please try again later.",
    );
    return;
  }

  try {
    const answer = await askGemini(apiKey, question);
    await editInteractionReply(applicationId, interactionToken, answer);
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    console.error("discord_ask_gemini_failed", msg.slice(0, 200));
    await editInteractionReply(
      applicationId,
      interactionToken,
      "I hit an AI error just now. Try again in a bit.",
    );
  }
}

export async function handleDiscordInteractionsRequest(
  request: Request,
  env: DiscordEnv,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Promise<Response> {
  if (request.method !== "POST") {
    return new Response("Method Not Allowed", { status: 405 });
  }

  const publicKey = (env.DISCORD_PUBLIC_KEY || "").trim();
  if (!publicKey) {
    return jsonResponse({ error: "discord_not_configured" }, 503);
  }

  const signature = request.headers.get("X-Signature-Ed25519") || "";
  const timestamp = request.headers.get("X-Signature-Timestamp") || "";
  const body = await request.text();

  const valid = await verifyDiscordSignature(body, signature, timestamp, publicKey);
  if (!valid) {
    return new Response("invalid request signature", { status: 401 });
  }

  let interaction: Interaction;
  try {
    interaction = JSON.parse(body) as Interaction;
  } catch {
    return jsonResponse({ error: "invalid_json" }, 400);
  }

  // 1 = PING
  if (interaction.type === 1) {
    return jsonResponse({ type: 1 });
  }

  // 2 = APPLICATION_COMMAND
  if (interaction.type === 2) {
    const name = interaction.data?.name || "";
    if (name !== "ask") {
      return jsonResponse({
        type: 4,
        data: { content: "Unknown command.", flags: 64 },
      });
    }

    const question = optionString(interaction, "question");
    if (!question) {
      return jsonResponse({
        type: 4,
        data: {
          content: "Ask something, e.g. `/ask question: How do I add my Gemini key?`",
          flags: 64,
        },
      });
    }
    if (question.length > MAX_QUESTION_CHARS) {
      return jsonResponse({
        type: 4,
        data: {
          content: `Keep questions under ${MAX_QUESTION_CHARS} characters.`,
          flags: 64,
        },
      });
    }

    const applicationId =
      (env.DISCORD_APPLICATION_ID || interaction.application_id || "").trim();
    if (!applicationId) {
      return jsonResponse({
        type: 4,
        data: { content: "Bot application id isn’t configured.", flags: 64 },
      });
    }

    if (ctx?.waitUntil) {
      ctx.waitUntil(fulfillAsk(env, applicationId, interaction.token, question));
    } else {
      // Local/tests without ExecutionContext — still defer then await.
      void fulfillAsk(env, applicationId, interaction.token, question);
    }

    // 5 = DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE (shows “thinking…”)
    return jsonResponse({ type: 5 });
  }

  return jsonResponse({ error: "unhandled_interaction_type" }, 400);
}
