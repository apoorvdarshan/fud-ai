/**
 * Hourly Play track check. Posts once in #announcements when a new Android
 * production build is actually live on the Play Store page — the track API
 * reports "completed" while Google is still reviewing, so the public listing
 * is checked before announcing. Open testing is not announced.
 */

import { ANNOUNCEMENTS_CHANNEL_ID, postAnnouncement } from "./discord-announce";

export { ANNOUNCEMENTS_CHANNEL_ID };

const PACKAGE_NAME = "com.apoorvdarshan.calorietracker";
const PLAY_DETAILS_URL =
  `https://play.google.com/store/apps/details?id=${PACKAGE_NAME}&hl=en&gl=US`;
const STATE_KEY = "android-play-announcements-v1";
const PLAY_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const TOKEN_URL = "https://oauth2.googleapis.com/token";

export type PlayTrackName = "production";

export type PlayRelease = {
  track: PlayTrackName;
  versionCode: string;
  name: string;
  whatsNew: string;
};

type StoredReleases = Record<PlayTrackName, string>;

type ServiceAccount = {
  client_email: string;
  private_key: string;
};

export type PlayAnnounceEnv = {
  DISCORD_BOT_TOKEN?: string;
  PLAY_SERVICE_ACCOUNT_JSON?: string;
  STAR_HISTORY: {
    get(key: string): Promise<string | null>;
    put(key: string, value: string): Promise<unknown>;
  };
};

/** The Discord message for a live Android release, within Discord's limit. */
export function announcementText(release: PlayRelease): string {
  const title = release.name || `build ${release.versionCode}`;
  const lead = `Android ${title} (${release.versionCode}) is on the Play Store.`;
  const notes = release.whatsNew.trim();
  const whatsNew = notes ? `\n\nWhat's new:\n${notes}` : "";
  const text = `${lead}${whatsNew}`;
  return text.length <= 2000 ? text : `${text.slice(0, 1997)}...`;
}

/** Releases whose version code differs from the last recorded one. */
export function releasesToAnnounce(
  previous: StoredReleases | null,
  current: PlayRelease[],
): PlayRelease[] {
  if (!previous) return [];
  return current.filter((release) => release.versionCode !== "" && previous[release.track] !== release.versionCode);
}

const HTML_ENTITIES: Record<string, string> = {
  "&amp;": "&",
  "&#39;": "'",
  "&quot;": '"',
  "&nbsp;": " ",
};

function decodeEntities(text: string): string {
  return text.replace(/&(?:amp|#39|quot|nbsp);/g, (match) => HTML_ENTITIES[match] ?? match);
}

function collapse(text: string): string {
  return decodeEntities(text.replace(/<[^>]*>/g, " "))
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

/** The live store listing's "What's new" block, or "" when absent. */
export function playStoreWhatsNew(html: string): string {
  const start = html.search(/What(?:'|’|&#39;|&rsquo;)s new/i);
  if (start < 0) return "";
  const slice = html.slice(start, start + 6000);
  const end = slice.search(/flag Flag as inappropriate|Data safety|You might also like/i);
  return end > 0 ? slice.slice(0, end) : slice;
}

/**
 * True only when the public Play listing carries this release's own notes.
 * A reused marketing version name is not proof of a new build, so the name is
 * only consulted for releases that ship without notes. Google keeps the store
 * page on the old copy while an update is in review, so this distinguishes
 * "submitted" from "live".
 */
export function playStoreShowsRelease(html: string, release: PlayRelease): boolean {
  const live = collapse(playStoreWhatsNew(html));
  if (!live) return false;
  const notes = collapse(release.whatsNew);
  if (notes.length > 0) return live.includes(notes);
  const name = collapse(release.name);
  return name.length > 0 && live.includes(name);
}

/** The public listing HTML. Throws so the hourly job reports the failure. */
async function fetchPlayStoreHtml(fetchImpl: typeof fetch): Promise<string> {
  let response: Response;
  try {
    response = await fetchImpl(PLAY_DETAILS_URL, {
      headers: {
        "User-Agent": "Mozilla/5.0 (compatible; fud-ai-play-announce/1.0)",
        "Accept-Language": "en-US,en;q=0.9",
      },
    });
  } catch {
    throw new Error("play_listing_request_failed");
  }
  if (!response.ok) throw new Error(`play_listing_failed_${response.status}`);
  return await response.text();
}

/**
 * Announces a new completed production release once its notes are visible on
 * the public listing. Returns early when nothing new is pending.
 */
export async function announceAndroidPlayReleases(
  env: PlayAnnounceEnv,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  const token = (env.DISCORD_BOT_TOKEN || "").trim();
  const serviceAccountJson = (env.PLAY_SERVICE_ACCOUNT_JSON || "").trim();
  if (!token || !serviceAccountJson) return;

  const current = await fetchPlayReleases(serviceAccountJson, fetchImpl);
  const previous = await readState(env);

  if (previous) {
    const pending = releasesToAnnounce(previous, current);
    if (pending.length === 0) return;
    const storeHtml = await fetchPlayStoreHtml(fetchImpl);
    const next: StoredReleases = { ...previous };
    for (const release of pending) {
      if (!playStoreShowsRelease(storeHtml, release)) continue;
      await postAnnouncement(token, announcementText(release), fetchImpl);
      next[release.track] = release.versionCode;
      await writeStateFromMap(env, next);
    }
    return;
  }

  // First run: seed only what the public listing already serves. A release
  // still in Google review stays unseeded so it is announced once it is live.
  const storeHtml = await fetchPlayStoreHtml(fetchImpl);
  const live = current.find((release) => playStoreShowsRelease(storeHtml, release));
  await writeStateFromMap(env, { production: live?.versionCode ?? "" });
}

async function readState(env: PlayAnnounceEnv): Promise<StoredReleases | null> {
  const raw = await env.STAR_HISTORY.get(STATE_KEY);
  if (!raw) return null;
  const parsed = JSON.parse(raw) as Partial<StoredReleases> & { beta?: string };
  if (typeof parsed.production !== "string") return null;
  return { production: parsed.production };
}

async function writeStateFromMap(env: PlayAnnounceEnv, state: StoredReleases): Promise<void> {
  await env.STAR_HISTORY.put(STATE_KEY, JSON.stringify({ production: state.production }));
}

async function fetchPlayReleases(
  serviceAccountJson: string,
  fetchImpl: typeof fetch,
): Promise<PlayRelease[]> {
  const account = JSON.parse(serviceAccountJson) as ServiceAccount;
  const accessToken = await googleAccessToken(account, fetchImpl);
  const headers = { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" };
  const root = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}`;
  const editResponse = await fetchImpl(`${root}/edits`, { method: "POST", headers, body: "{}" });
  if (!editResponse.ok) throw new Error(`play_edit_failed_${editResponse.status}`);
  const edit = await editResponse.json() as { id: string };
  try {
    const tracksResponse = await fetchImpl(`${root}/edits/${edit.id}/tracks`, { headers });
    if (!tracksResponse.ok) throw new Error(`play_tracks_failed_${tracksResponse.status}`);
    const body = await tracksResponse.json() as { tracks?: RawTrack[] };
    return [pickRelease(body.tracks, "production")];
  } finally {
    await fetchImpl(`${root}/edits/${edit.id}`, { method: "DELETE", headers }).catch(() => undefined);
  }
}

type RawTrack = {
  track?: string;
  releases?: Array<{
    name?: string;
    status?: string;
    versionCodes?: string[];
    releaseNotes?: Array<{ language?: string; text?: string }>;
  }>;
};

function pickRelease(tracks: RawTrack[] | undefined, track: PlayTrackName): PlayRelease {
  let versionCode = "";
  let name = "";
  let whatsNew = "";
  for (const release of (tracks ?? []).find((item) => item.track === track)?.releases ?? []) {
    if (release.status !== "completed") continue;
    for (const code of release.versionCodes ?? []) {
      if (!/^\d+$/.test(code)) continue;
      if (versionCode !== "" && Number(code) <= Number(versionCode)) continue;
      versionCode = code;
      name = (release.name || "").trim();
      const notes = release.releaseNotes ?? [];
      const english = notes.find((note) => note.language === "en-US")
        ?? notes.find((note) => note.language?.startsWith("en"));
      whatsNew = (english?.text || "").trim();
    }
  }
  return { track, versionCode, name, whatsNew };
}

async function googleAccessToken(account: ServiceAccount, fetchImpl: typeof fetch): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const assertion = await signServiceAccountJwt(account, now);
  const response = await fetchImpl(TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error(`play_token_failed_${response.status}`);
  const body = await response.json() as { access_token?: string };
  if (!body.access_token) throw new Error("play_token_missing");
  return body.access_token;
}

async function signServiceAccountJwt(account: ServiceAccount, now: number): Promise<string> {
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    iss: account.client_email,
    scope: PLAY_SCOPE,
    aud: TOKEN_URL,
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${header}.${payload}`;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
}

function pemToBytes(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

function base64Url(value: string): string {
  return base64UrlBytes(new TextEncoder().encode(value));
}

function base64UrlBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
