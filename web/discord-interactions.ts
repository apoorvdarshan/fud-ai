/**
 * Discord HTTP interactions for `/ask`, `/bug`, and `/feature` (Cloudflare Worker).
 *
 * Slash commands are delivered to the Interactions Endpoint URL — no Discord
 * gateway / always-on VM. Mentions (@Fud AI) are not handled here.
 */

export const DISCORD_INTERACTIONS_PATH = "/api/discord/interactions";

export const IOS_BUG_CHANNEL_ID = "1548481436129165353";
export const ANDROID_BUG_CHANNEL_ID = "1548481448024084540";

const GITHUB_ISSUES_REPO = "apoorvdarshan/fud-ai";
const GITHUB_ISSUES_URL = `https://api.github.com/repos/${GITHUB_ISSUES_REPO}/issues`;
const GITHUB_API_VERSION = "2022-11-28";
const GITHUB_TITLE_MAX = 256;

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
  /** Shared with star history; `/bug` and `/feature` need `issues:write` on apoorvdarshan/fud-ai. */
  GITHUB_TOKEN?: string;
};

type DiscordUser = {
  id?: string;
  username?: string;
  global_name?: string;
};

type Interaction = {
  type: number;
  token: string;
  id: string;
  application_id?: string;
  channel_id?: string;
  channel?: { id?: string };
  guild_id?: string;
  guild?: { id?: string };
  data?: {
    name?: string;
    options?: Array<{ name?: string; type?: number; value?: unknown }>;
  };
  member?: { user?: DiscordUser };
  user?: DiscordUser;
};

export type BugPlatform = "iOS" | "Android" | "";
export type FeaturePlatform = "iOS" | "Android" | "both" | "";

/** Matches `.github/ISSUE_TEMPLATE/feature_request.yml` (`enhancement` exists on the repo). */
export const FEATURE_ISSUE_LABEL = "enhancement";

type DiscordReporter = {
  channelId: string;
  guildId: string;
  userId: string;
  username: string;
};

type BugReport = DiscordReporter & {
  title: string;
  details: string;
  device: string;
  appVersion: string;
  platform: BugPlatform;
};

type FeatureReport = DiscordReporter & {
  title: string;
  details: string;
  platform: FeaturePlatform;
};

type GitHubIssueDraft = {
  title: string;
  body: string;
  labels: string[];
  fallbackLabels?: string[];
  userAgent: string;
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

function interactionChannelId(interaction: Interaction): string {
  return (interaction.channel_id || interaction.channel?.id || "").trim();
}

function interactionGuildId(interaction: Interaction): string {
  return (interaction.guild_id || interaction.guild?.id || "").trim();
}

function reporterFrom(interaction: Interaction): { id: string; username: string } {
  const user = interaction.member?.user ?? interaction.user;
  const username = (user?.global_name || user?.username || "unknown").trim() || "unknown";
  const id = (user?.id || "unknown").trim() || "unknown";
  return { id, username };
}

function normalizeBugPlatform(value: string): BugPlatform {
  const lowered = value.trim().toLowerCase();
  if (lowered === "ios") return "iOS";
  if (lowered === "android") return "Android";
  return "";
}

/** Prefer the `/bug platform` option; otherwise infer from the iOS/Android channels. */
export function resolveBugPlatform(option: string, channelId: string): BugPlatform {
  const fromOption = normalizeBugPlatform(option);
  if (fromOption) return fromOption;
  if (channelId === IOS_BUG_CHANNEL_ID) return "iOS";
  if (channelId === ANDROID_BUG_CHANNEL_ID) return "Android";
  return "";
}

export function labelsForBugPlatform(platform: BugPlatform): string[] {
  const labels = ["bug"];
  if (platform === "iOS") labels.push("ios");
  if (platform === "Android") labels.push("android");
  return labels;
}

function normalizeFeaturePlatform(value: string): FeaturePlatform {
  const lowered = value.trim().toLowerCase();
  if (lowered === "ios") return "iOS";
  if (lowered === "android") return "Android";
  if (lowered === "both") return "both";
  return "";
}

/** Optional `/feature platform` only — never inferred from channel. */
export function resolveFeaturePlatform(option: string): FeaturePlatform {
  return normalizeFeaturePlatform(option);
}

export function labelsForFeatureRequest(): string[] {
  return [FEATURE_ISSUE_LABEL];
}

function clipGitHubTitle(title: string): string {
  if (title.length <= GITHUB_TITLE_MAX) return title;
  return `${title.slice(0, GITHUB_TITLE_MAX - 1)}…`;
}

function formatBugIssueBody(report: BugReport): string {
  const device = report.device || "_Not provided_";
  const appVersion = report.appVersion || "_Not provided_";
  const platform = report.platform || "_Unknown_";
  return [
    "## Details",
    "",
    report.details,
    "",
    "## Device",
    "",
    device,
    "",
    "## App version",
    "",
    appVersion,
    "",
    "## Platform",
    "",
    platform,
    "",
    formatDiscordIssueFooter("Reported via Discord `/bug`", report),
  ].join("\n");
}

function formatFeatureIssueBody(report: FeatureReport): string {
  const platform = report.platform || "_Not specified_";
  return [
    "## Summary",
    "",
    report.details,
    "",
    "## Platform",
    "",
    platform,
    "",
    formatDiscordIssueFooter("Opened via Discord `/feature`", report),
  ].join("\n");
}

function formatDiscordIssueFooter(intro: string, report: DiscordReporter): string {
  return [
    "---",
    "",
    intro,
    "",
    `- **User:** ${report.username} (\`${report.userId}\`)`,
    `- **Channel:** \`${report.channelId || "unknown"}\``,
    `- **Guild:** \`${report.guildId || "unknown"}\``,
  ].join("\n");
}

async function createGitHubIssue(
  token: string,
  draft: GitHubIssueDraft,
): Promise<{ htmlUrl: string; number: number }> {
  const payload = {
    title: clipGitHubTitle(draft.title),
    body: draft.body,
    labels: draft.labels,
  };

  const respond = async (labels: string[]): Promise<Response> =>
    fetch(GITHUB_ISSUES_URL, {
      method: "POST",
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        "User-Agent": draft.userAgent,
        "X-GitHub-Api-Version": GITHUB_API_VERSION,
      },
      body: JSON.stringify({ ...payload, labels }),
    });

  let resp = await respond(payload.labels);
  const fallback = draft.fallbackLabels;
  if (
    resp.status === 422 &&
    fallback &&
    fallback.join("\0") !== payload.labels.join("\0")
  ) {
    // Repo may not have extra labels (e.g. `ios` / `android`) yet — still file.
    resp = await respond(fallback);
  }

  const data = (await resp.json()) as {
    html_url?: string;
    number?: number;
    message?: string;
  };
  if (!resp.ok || !data.html_url || typeof data.number !== "number") {
    throw new Error(data.message || `GitHub HTTP ${resp.status}`);
  }
  return { htmlUrl: data.html_url, number: data.number };
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

async function fulfillBug(
  env: DiscordEnv,
  applicationId: string,
  interactionToken: string,
  report: BugReport,
): Promise<void> {
  const token = (env.GITHUB_TOKEN || "").trim();
  if (!token) {
    await editInteractionReply(
      applicationId,
      interactionToken,
      "GitHub isn’t configured on the server yet. Please try again later.",
    );
    return;
  }

  try {
    const issue = await createGitHubIssue(token, {
      title: report.title,
      body: formatBugIssueBody(report),
      labels: labelsForBugPlatform(report.platform),
      fallbackLabels: ["bug"],
      userAgent: "fud-ai-discord-bug",
    });
    await editInteractionReply(
      applicationId,
      interactionToken,
      `Opened ${issue.htmlUrl}`,
    );
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    console.error("discord_bug_github_failed", msg.slice(0, 200));
    await editInteractionReply(
      applicationId,
      interactionToken,
      "I couldn’t create the GitHub issue just now. Try again in a bit, or file it at https://github.com/apoorvdarshan/fud-ai/issues/new?template=bug_report.yml",
    );
  }
}

async function fulfillFeature(
  env: DiscordEnv,
  applicationId: string,
  interactionToken: string,
  report: FeatureReport,
): Promise<void> {
  const token = (env.GITHUB_TOKEN || "").trim();
  if (!token) {
    await editInteractionReply(
      applicationId,
      interactionToken,
      "GitHub isn’t configured on the server yet. Please try again later.",
    );
    return;
  }

  try {
    const issue = await createGitHubIssue(token, {
      title: report.title,
      body: formatFeatureIssueBody(report),
      labels: labelsForFeatureRequest(),
      userAgent: "fud-ai-discord-feature",
    });
    await editInteractionReply(
      applicationId,
      interactionToken,
      `Opened ${issue.htmlUrl}`,
    );
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    console.error("discord_feature_github_failed", msg.slice(0, 200));
    await editInteractionReply(
      applicationId,
      interactionToken,
      "I couldn’t create the GitHub issue just now. Try again in a bit, or file it at https://github.com/apoorvdarshan/fud-ai/issues/new?template=feature_request.yml",
    );
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

function handleBugCommand(
  env: DiscordEnv,
  interaction: Interaction,
  applicationId: string,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Response {
  const title = optionString(interaction, "title");
  const details = optionString(interaction, "details");
  if (!title || !details) {
    return jsonResponse({
      type: 4,
      data: {
        content: "Need both `title` and `details` — e.g. `/bug title: Crash on save details: Steps to reproduce…`",
        flags: 64,
      },
    });
  }

  if (!applicationId) {
    return jsonResponse({
      type: 4,
      data: { content: "Bot application id isn’t configured.", flags: 64 },
    });
  }

  const channelId = interactionChannelId(interaction);
  const reporter = reporterFrom(interaction);
  const report: BugReport = {
    title,
    details,
    device: optionString(interaction, "device"),
    appVersion: optionString(interaction, "app_version"),
    platform: resolveBugPlatform(optionString(interaction, "platform"), channelId),
    channelId,
    guildId: interactionGuildId(interaction),
    userId: reporter.id,
    username: reporter.username,
  };

  if (ctx?.waitUntil) {
    ctx.waitUntil(fulfillBug(env, applicationId, interaction.token, report));
  } else {
    void fulfillBug(env, applicationId, interaction.token, report);
  }

  // 5 = DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE (shows “thinking…”)
  return jsonResponse({ type: 5 });
}

function handleFeatureCommand(
  env: DiscordEnv,
  interaction: Interaction,
  applicationId: string,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Response {
  const title = optionString(interaction, "title");
  const details = optionString(interaction, "details");
  if (!title || !details) {
    return jsonResponse({
      type: 4,
      data: {
        content: "Need both `title` and `details` — e.g. `/feature title: Widget calories details: Show remaining calories on the home screen widget.`",
        flags: 64,
      },
    });
  }

  if (!applicationId) {
    return jsonResponse({
      type: 4,
      data: { content: "Bot application id isn’t configured.", flags: 64 },
    });
  }

  const reporter = reporterFrom(interaction);
  const report: FeatureReport = {
    title,
    details,
    platform: resolveFeaturePlatform(optionString(interaction, "platform")),
    channelId: interactionChannelId(interaction),
    guildId: interactionGuildId(interaction),
    userId: reporter.id,
    username: reporter.username,
  };

  if (ctx?.waitUntil) {
    ctx.waitUntil(fulfillFeature(env, applicationId, interaction.token, report));
  } else {
    void fulfillFeature(env, applicationId, interaction.token, report);
  }

  // 5 = DEFERRED_CHANNEL_MESSAGE_WITH_SOURCE (shows “thinking…”)
  return jsonResponse({ type: 5 });
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
    const applicationId =
      (env.DISCORD_APPLICATION_ID || interaction.application_id || "").trim();

    if (name === "bug") {
      return handleBugCommand(env, interaction, applicationId, ctx);
    }

    if (name === "feature") {
      return handleFeatureCommand(env, interaction, applicationId, ctx);
    }

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
