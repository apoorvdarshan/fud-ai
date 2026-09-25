/**
 * Hourly Play track check. Posts once in #announcements when Android
 * production serves a new version code. #beta-android stays for /bug.
 */

export const ANNOUNCEMENTS_CHANNEL_ID = "1548481417728495678";
const PACKAGE_NAME = "com.apoorvdarshan.calorietracker";
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

export function announcementText(release: PlayRelease): string {
  const title = release.name || `build ${release.versionCode}`;
  const lead = `Android ${title} (${release.versionCode}) is on the Play Store.`;
  const notes = release.whatsNew.trim();
  const whatsNew = notes ? `\n\nWhat's new:\n${notes}` : "";
  const text = `${lead}${whatsNew}`;
  return text.length <= 2000 ? text : `${text.slice(0, 1997)}...`;
}

export function releasesToAnnounce(
  previous: StoredReleases | null,
  current: PlayRelease[],
): PlayRelease[] {
  if (!previous) return [];
  return current.filter((release) => release.versionCode !== "" && previous[release.track] !== release.versionCode);
}

export async function announceAndroidPlayReleases(
  env: PlayAnnounceEnv,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  const token = (env.DISCORD_BOT_TOKEN || "").trim();
  const serviceAccountJson = (env.PLAY_SERVICE_ACCOUNT_JSON || "").trim();
  if (!token || !serviceAccountJson) return;

  const current = await fetchPlayReleases(serviceAccountJson, fetchImpl);
  const previous = await readState(env);
  if (!previous) {
    await writeState(env, current);
    return;
  }
  const pending = releasesToAnnounce(previous, current);
  const next: StoredReleases = { ...previous };
  for (const release of pending) {
    await postAnnouncement(token, announcementText(release), fetchImpl);
    next[release.track] = release.versionCode;
    await writeStateFromMap(env, next);
  }
}

async function readState(env: PlayAnnounceEnv): Promise<StoredReleases | null> {
  const raw = await env.STAR_HISTORY.get(STATE_KEY);
  if (!raw) return null;
  const parsed = JSON.parse(raw) as Partial<StoredReleases> & { beta?: string };
  if (typeof parsed.production !== "string") return null;
  return { production: parsed.production };
}

async function writeState(env: PlayAnnounceEnv, releases: PlayRelease[]): Promise<void> {
  const state: StoredReleases = { production: "" };
  for (const release of releases) state[release.track] = release.versionCode;
  await writeStateFromMap(env, state);
}

async function writeStateFromMap(env: PlayAnnounceEnv, state: StoredReleases): Promise<void> {
  await env.STAR_HISTORY.put(STATE_KEY, JSON.stringify({ production: state.production }));
}

async function postAnnouncement(
  token: string,
  content: string,
  fetchImpl: typeof fetch,
): Promise<void> {
  const response = await fetchImpl(
    `https://discord.com/api/v10/channels/${ANNOUNCEMENTS_CHANNEL_ID}/messages`,
    {
      method: "POST",
      headers: {
        Authorization: `Bot ${token}`,
        "Content-Type": "application/json",
        "User-Agent": "fud-ai-play-announce",
      },
      body: JSON.stringify({ content, allowed_mentions: { parse: [] } }),
    },
  );
  if (!response.ok) {
    throw new Error(`discord_announce_failed_${response.status}`);
  }
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
