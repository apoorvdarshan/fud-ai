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
const ISSUE_TITLE_LINE_MAX = 100;
const ISSUE_TITLE_CLIP_MIN = 80;
const ISSUE_TITLE_CLIP_MAX = 100;

const GEMINI_MODEL = "gemini-3.5-flash-lite";
const MAX_QUESTION_CHARS = 1_500;
const MAX_REPORT_CHARS = 4_000;
const MAX_REPLY_CHARS = 1_800;
const ASK_COOLDOWN_SECONDS = 60;
const REPORT_COOLDOWN_SECONDS = 300;
const INTERACTION_RETENTION_SECONDS = 900;

type DiscordState = {
  get(key: string): Promise<string | null>;
  getWithMetadata(key: string): Promise<{ value: string | null; metadata: unknown }>;
  put(key: string, value: string, options: { expirationTtl: number; metadata?: { owner: string } }): Promise<void>;
};

// Isolate-local reservations close concurrent-request races while KV persists across restarts.
type LocalReservation = { until: number; unavailable?: boolean };
const localReservations = new WeakMap<DiscordState, Map<string, LocalReservation>>();

const SYSTEM_PROMPT = `You are the Fud AI Discord helper for the free, open-source calorie / fasting / workout tracker (iOS + Android).

Be friendly and concise (Discord-length answers).

Facts:
- No Fud AI account required; privacy-first / local-first.
- AI uses bring-your-own-key (BYOK) or optional hosted AI on iOS.
- Website: https://fud-ai.app
- Source / issues: https://github.com/apoorvdarshan/fud-ai
- Discord: https://discord.gg/Py4VrFctP3 — in that server use the /ask slash command for help from this bot

Not a doctor — no medical advice or diagnoses. If unsure about the app, point to GitHub issues or in-app Settings.`;

const STRUCTURE_PROMPT = `You turn a Discord user's freeform Fud AI report into a GitHub issue draft.

Return ONLY JSON with this shape:
{"title":"...","body":"...","platform":"iOS"|"Android"|null}

Rules:
- title: concise and specific, at most 100 characters. No Bug:/Feature: prefix.
- body: markdown that organizes what the user actually wrote. You may add headings.
- platform: if kind is bug, "iOS" or "Android" only when the report clearly indicates one mobile platform (including device names such as iPhone, iPad, Pixel, Galaxy, OnePlus, Samsung). Use null if unknown, unspecified, or both platforms are mentioned. If kind is feature, use null.
- Do not invent devices, OS versions, steps, product behavior, or a platform they did not mention.
- Keep their facts; clean up structure and wording.
- If kind is bug, emphasize what broke and how to reproduce.
- If kind is feature, emphasize the request and why it would help.`;

type DiscordEnv = {
  DISCORD_PUBLIC_KEY: string;
  DISCORD_APPLICATION_ID?: string;
  /** Free-tier Gemini key for Discord `/ask` only (never use hosted GEMINI_API_KEY). */
  DISCORD_GEMINI_API_KEY?: string;
  /** Shared with star history; `/bug` and `/feature` need `issues:write` on apoorvdarshan/fud-ai. */
  GITHUB_TOKEN?: string;
  /** Dedicated short-lived cooldown/replay state; never store prompts or credentials. */
  DISCORD_STATE?: DiscordState;
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
  report: string;
  platform: BugPlatform;
};

type FeatureReport = DiscordReporter & {
  report: string;
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

function normalizeStructuredPlatform(value: unknown): BugPlatform | null {
  if (typeof value !== "string") return null;
  return normalizeBugPlatform(value) || null;
}

/** Word-boundary hints in freeform `/bug report` text (and Gemini drafts). */
const IOS_PLATFORM_HINT = /\b(?:ios|iphone|ipad|ipod|ipados)\b/i;
const ANDROID_PLATFORM_HINT =
  /\b(?:android|pixel|galaxy|oneplus|one\s+plus|samsung)\b/i;

export type BugPlatformSignals = { ios: boolean; android: boolean };

export type StructuredIssue = {
  title: string;
  body: string;
  platform: BugPlatform | null;
};

export function detectBugPlatformSignals(text: string): BugPlatformSignals {
  return {
    ios: IOS_PLATFORM_HINT.test(text),
    android: ANDROID_PLATFORM_HINT.test(text),
  };
}

export function platformFromBugChannel(channelId: string): BugPlatform {
  if (channelId === IOS_BUG_CHANNEL_ID) return "iOS";
  if (channelId === ANDROID_BUG_CHANNEL_ID) return "Android";
  return "";
}

/**
 * Prefer a clear platform in the report (and/or Gemini draft). If both
 * platforms are strongly mentioned, use the iOS/Android bug channel when
 * known; otherwise omit. Channel is only a fallback when text is silent.
 */
export function resolveBugPlatform(
  reportText: string,
  channelId: string,
  gemini?: Pick<StructuredIssue, "platform" | "title" | "body"> | null,
): BugPlatform {
  const haystack = [reportText, gemini?.title, gemini?.body]
    .filter((part): part is string => Boolean(part?.trim()))
    .join("\n");
  const signals = detectBugPlatformSignals(haystack);
  if (gemini?.platform === "iOS") signals.ios = true;
  if (gemini?.platform === "Android") signals.android = true;

  if (signals.ios && signals.android) return platformFromBugChannel(channelId);
  if (signals.ios) return "iOS";
  if (signals.android) return "Android";
  return platformFromBugChannel(channelId);
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

/** First short line, else ~80–100 chars at a word boundary. */
export function deriveIssueTitle(report: string): string {
  const text = report.trim();
  if (!text) return "Discord report";

  const firstLine =
    text
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line.length > 0) ?? "";
  if (firstLine && firstLine.length <= ISSUE_TITLE_LINE_MAX) return firstLine;

  const flattened = text.replace(/\s+/g, " ").trim();
  if (flattened.length <= ISSUE_TITLE_CLIP_MAX) return flattened;

  const window = flattened.slice(0, ISSUE_TITLE_CLIP_MAX);
  const breakAt = window.lastIndexOf(" ");
  const clipped = breakAt >= ISSUE_TITLE_CLIP_MIN ? window.slice(0, breakAt) : window;
  return `${clipped.trimEnd()}…`;
}

function formatDiscordIssueFooter(
  intro: string,
  report: DiscordReporter & { platform?: string },
): string {
  const lines = [
    "---",
    "",
    intro,
    "",
    `- **User:** ${report.username} (\`${report.userId}\`)`,
    `- **Channel:** \`${report.channelId || "unknown"}\``,
    `- **Guild:** \`${report.guildId || "unknown"}\``,
  ];
  if (report.platform) {
    lines.push(`- **Platform:** ${report.platform}`);
  }
  return lines.join("\n");
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

async function generateDiscordGeminiText(
  apiKey: string,
  systemPrompt: string,
  userText: string,
  generationConfig: { temperature: number; maxOutputTokens: number },
): Promise<string> {
  const url =
    `https://generativelanguage.googleapis.com/v1beta/models/` +
    `${GEMINI_MODEL}:generateContent?key=${apiKey}`;

  const resp = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: systemPrompt }] },
      contents: [{ role: "user", parts: [{ text: userText }] }],
      generationConfig,
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
  return text;
}

async function askGemini(apiKey: string, question: string): Promise<string> {
  const text = await generateDiscordGeminiText(apiKey, SYSTEM_PROMPT, question, {
    temperature: 0.7,
    maxOutputTokens: 512,
  });
  return text.length > MAX_REPLY_CHARS ? `${text.slice(0, MAX_REPLY_CHARS - 1)}…` : text;
}

export function parseStructuredIssue(text: string): StructuredIssue | null {
  const trimmed = text.trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/);
  const raw = (fenced?.[1] ?? trimmed).trim();
  try {
    const parsed = JSON.parse(raw) as {
      title?: unknown;
      body?: unknown;
      platform?: unknown;
    };
    if (typeof parsed.title !== "string" || typeof parsed.body !== "string") return null;
    const title = parsed.title.trim();
    const body = parsed.body.trim();
    if (!title || !body) return null;
    return { title, body, platform: normalizeStructuredPlatform(parsed.platform) };
  } catch {
    return null;
  }
}

async function structureReportWithGemini(
  apiKey: string,
  kind: "bug" | "feature",
  report: string,
): Promise<StructuredIssue | null> {
  try {
    const text = await generateDiscordGeminiText(
      apiKey,
      STRUCTURE_PROMPT,
      `kind: ${kind}\n\nreport:\n${report}`,
      { temperature: 0.2, maxOutputTokens: 1024 },
    );
    return parseStructuredIssue(text);
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    console.error("discord_report_gemini_failed", msg.slice(0, 200));
    return null;
  }
}

async function draftGitHubIssue(
  env: DiscordEnv,
  kind: "bug" | "feature",
  report: DiscordReporter & { report: string; platform?: string },
  footerIntro: string,
): Promise<{ title: string; body: string; platform: string }> {
  // Free-tier Discord key only — never GEMINI_API_KEY (hosted/billed).
  const apiKey = (env.DISCORD_GEMINI_API_KEY || "").trim();
  const structured = apiKey
    ? await structureReportWithGemini(apiKey, kind, report.report)
    : null;
  const platform =
    kind === "bug"
      ? resolveBugPlatform(report.report, report.channelId, structured)
      : (report.platform ?? "");
  const title = structured?.title || deriveIssueTitle(report.report);
  const main = structured?.body || report.report;
  return {
    title,
    body: [main, "", formatDiscordIssueFooter(footerIntro, { ...report, platform })].join("\n"),
    platform,
  };
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
    const draft = await draftGitHubIssue(
      env,
      "bug",
      report,
      "Reported via Discord `/bug`",
    );
    const platform: BugPlatform =
      draft.platform === "iOS" || draft.platform === "Android" ? draft.platform : "";
    const issue = await createGitHubIssue(token, {
      title: draft.title,
      body: draft.body,
      labels: labelsForBugPlatform(platform),
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
    const draft = await draftGitHubIssue(
      env,
      "feature",
      report,
      "Opened via Discord `/feature`",
    );
    const issue = await createGitHubIssue(token, {
      title: draft.title,
      body: draft.body,
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

async function digestKey(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

class DiscordStateTimeout extends Error {}

async function stateOperation<T>(operation: Promise<T>): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      operation,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new DiscordStateTimeout("discord_state_timeout")), 1_500);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/** Best-effort cross-isolate limits: KV is eventually consistent, not an atomic lock. */
async function reserveCommand(
  state: DiscordState,
  interaction: Interaction,
  applicationId: string,
  command: "ask" | "bug" | "feature",
  waitUntil: (promise: Promise<unknown>) => void,
): Promise<"accepted" | "duplicate" | "cooldown" | "unavailable"> {
  let stage: "hash" | "read" | "write" = "hash";
  let release: (() => void) | undefined;
  let rollback: (() => Promise<void>) | undefined;
  try {
    const userId = reporterFrom(interaction).id;
    const scope = interactionGuildId(interaction) || "dm";
    const bucket = command === "ask" ? "ask" : "report";
    const cooldown = command === "ask" ? ASK_COOLDOWN_SECONDS : REPORT_COOLDOWN_SECONDS;
    const [interactionHash, cooldownHash] = await Promise.all([
      digestKey(`${applicationId}:${interaction.id}`),
      digestKey(`${applicationId}:${scope}:${userId}:${bucket}`),
    ]);
    const interactionKey = `fuddy:interaction:${interactionHash}`;
    const cooldownKey = `fuddy:cooldown:${cooldownHash}`;
    let local = localReservations.get(state);
    if (!local) {
      local = new Map();
      localReservations.set(state, local);
    }
    const now = Date.now();
    for (const [key, reservation] of local) {
      if (reservation.until <= now) local.delete(key);
    }
    if (local.get(interactionKey)?.unavailable || local.get(cooldownKey)?.unavailable) {
      return "unavailable";
    }
    if ((local.get(interactionKey)?.until ?? 0) > now) return "duplicate";
    if ((local.get(cooldownKey)?.until ?? 0) > now) return "cooldown";
    while (local.size > 2_046) {
      const oldest = [...local].find(([, reservation]) => !reservation.unavailable)?.[0];
      if (oldest === undefined) return "unavailable";
      local.delete(oldest);
    }
    // Reserve before the first KV await so simultaneous commands in this isolate cannot pass.
    const interactionReservation: LocalReservation = { until: now + INTERACTION_RETENTION_SECONDS * 1000 };
    const cooldownReservation: LocalReservation = { until: now + cooldown * 1000 };
    local.set(interactionKey, interactionReservation);
    local.set(cooldownKey, cooldownReservation);
    release = () => {
      // An evicted/expired attempt must not clear a later local reservation.
      if (local.get(interactionKey) === interactionReservation) local.delete(interactionKey);
      if (local.get(cooldownKey) === cooldownReservation) local.delete(cooldownKey);
    };
    stage = "read";
    const [seen, cooling] = await stateOperation(Promise.all([
      state.get(interactionKey),
      state.getWithMetadata(cooldownKey),
    ]));
    const metadata = cooling.metadata;
    const coolingOwner = metadata && typeof metadata === "object" && "owner" in metadata
      && typeof metadata.owner === "string" ? metadata.owner : undefined;
    // Failed attempts are invalidated by an immutable, attempt-specific marker.
    // Never delete shared keys: KV has no atomic compare-and-delete operation.
    const [replayAborted, cooldownAborted] = await stateOperation(Promise.all([
      seen && seen !== "accepted" ? state.get(`fuddy:aborted:${seen}`) : Promise.resolve(null),
      coolingOwner ? state.get(`fuddy:aborted:${coolingOwner}`) : Promise.resolve(null),
    ]));
    if (seen && !replayAborted) {
      local.delete(cooldownKey);
      return "duplicate";
    }
    const storedUntil = Number(cooling.value);
    if (!cooldownAborted && storedUntil > Date.now()) {
      cooldownReservation.until = storedUntil;
      return "cooldown";
    }
    const until = Date.now() + cooldown * 1000;
    cooldownReservation.until = until;
    const owner = crypto.randomUUID();
    const records = [
      { key: interactionKey, value: owner, ttl: INTERACTION_RETENTION_SECONDS },
      { key: cooldownKey, value: String(until), ttl: cooldown },
    ];
    stage = "write";
    // Observe every write, including one that completes after our response deadline.
    const writes = records.map(({ key, value, ttl }) =>
      Promise.resolve().then(() => state.put(key, value, { expirationTtl: ttl, metadata: { owner } })));
    const settled = Promise.allSettled(writes);
    rollback = async () => {
      // Keep unresolved writes serialized locally; a late write must not overwrite
      // a newer local reservation. Pending guards are never evicted for capacity.
      interactionReservation.until = cooldownReservation.until = Infinity;
      interactionReservation.unavailable = cooldownReservation.unavailable = true;
      try {
        await settled;
        // Longer than either reservation TTL, starting only after all puts finish.
        // This key belongs solely to this attempt and cannot erase another owner.
        await state.put(`fuddy:aborted:${owner}`, "1", { expirationTtl: INTERACTION_RETENTION_SECONDS });
      } finally {
        release?.();
      }
    };
    await stateOperation(Promise.all(writes));
    return "accepted";
  } catch (error) {
    console.warn("discord_cooldown_state_unavailable", {
      stage,
      reason: error instanceof DiscordStateTimeout ? "timeout" : "operation_failed",
    });
    if (rollback) {
      const cleanup = rollback().catch(() => {
        console.warn("discord_cooldown_cleanup_failed", { stage: "rollback", reason: "operation_failed" });
      });
      // Keep cleanup alive after returning the unavailable reply, including late puts.
      waitUntil(cleanup);
    } else {
      release?.();
    }
    return "unavailable";
  }
}

function deferCommand(
  env: DiscordEnv,
  interaction: Interaction,
  applicationId: string,
  command: "ask" | "bug" | "feature",
  fulfill: () => Promise<void>,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Response {
  const state = env.DISCORD_STATE;
  if (!state || !ctx?.waitUntil) {
    return jsonResponse({
      type: 4,
      data: { content: "Fuddy’s command service is temporarily unavailable. Please try again later.", flags: 64 },
    });
  }
  if (!interaction.id || reporterFrom(interaction).id === "unknown") {
    return jsonResponse({
      type: 4,
      data: { content: "I couldn’t identify this command’s sender. Please try again.", flags: 64 },
    });
  }
  ctx.waitUntil((async () => {
    const reservation = await reserveCommand(state, interaction, applicationId, command, (promise) => ctx.waitUntil(promise));
    if (reservation === "duplicate") return;
    if (reservation === "accepted") {
      await fulfill();
      return;
    }
    const content = reservation === "cooldown"
      ? command === "ask"
        ? "Please wait a minute between questions."
        : "Please wait five minutes between reports. /bug and /feature share this cooldown. Your reports become public GitHub issues."
      : "Fuddy’s command service is temporarily unavailable. Please try again later.";
    await editInteractionReply(applicationId, interaction.token, content);
  })().catch(() => {
    // Do not log raw errors: Gemini request URLs include an API key.
    console.warn("discord_command_delivery_failed");
  }));
  // Defer before KV/AI/GitHub work so Discord receives its acknowledgement promptly.
  return jsonResponse({ type: 5 });
}

function handleBugCommand(
  env: DiscordEnv,
  interaction: Interaction,
  applicationId: string,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Response {
  const reportText = optionString(interaction, "report");
  if (!reportText) {
    return jsonResponse({
      type: 4,
      data: {
        content: "Paste the bug in `report` — e.g. `/bug report: Crash on save when I tap the checkmark.`",
        flags: 64,
      },
    });
  }
  if (reportText.length > MAX_REPORT_CHARS) {
    return jsonResponse({
      type: 4,
      data: {
        content: `Keep reports under ${MAX_REPORT_CHARS} characters.`,
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
    report: reportText,
    platform: "",
    channelId,
    guildId: interactionGuildId(interaction),
    userId: reporter.id,
    username: reporter.username,
  };

  return deferCommand(env, interaction, applicationId, "bug",
    () => fulfillBug(env, applicationId, interaction.token, report), ctx);
}

function handleFeatureCommand(
  env: DiscordEnv,
  interaction: Interaction,
  applicationId: string,
  ctx?: { waitUntil: (promise: Promise<unknown>) => void },
): Response {
  const reportText = optionString(interaction, "report");
  if (!reportText) {
    return jsonResponse({
      type: 4,
      data: {
        content: "Paste the request in `report` — e.g. `/feature report: Show remaining calories on the home screen widget.`",
        flags: 64,
      },
    });
  }
  if (reportText.length > MAX_REPORT_CHARS) {
    return jsonResponse({
      type: 4,
      data: {
        content: `Keep reports under ${MAX_REPORT_CHARS} characters.`,
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
    report: reportText,
    platform: resolveFeaturePlatform(optionString(interaction, "platform")),
    channelId: interactionChannelId(interaction),
    guildId: interactionGuildId(interaction),
    userId: reporter.id,
    username: reporter.username,
  };

  return deferCommand(env, interaction, applicationId, "feature",
    () => fulfillFeature(env, applicationId, interaction.token, report), ctx);
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

    return deferCommand(env, interaction, applicationId, "ask",
      () => fulfillAsk(env, applicationId, interaction.token, question), ctx);
  }

  return jsonResponse({ error: "unhandled_interaction_type" }, 400);
}
