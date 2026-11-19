/**
 * Hourly App Store check. Posts once in #announcements when a new iOS version
 * becomes the live App Store version, with the App Store "What's new" notes.
 *
 * Reads App Store Connect with a Customer Support API key: only the version in
 * READY_FOR_SALE is the one users can download, so it is already a liveness
 * signal. (The public iTunes lookup answers Cloudflare Workers with HTTP 403.)
 */

import { postAnnouncement } from "./discord-announce";
import { base64Url, base64UrlBytes, pemToBytes } from "./store-jwt";

const APP_ID = "6758935726";
const ASC_API = "https://api.appstoreconnect.apple.com";
const ASC_AUDIENCE = "appstoreconnect-v1";
// Apple rejects tokens that live longer than 20 minutes.
const TOKEN_LIFETIME_SECONDS = 600;
const NOTES_LOCALE = "en-US";
const STATE_KEY = "ios-appstore-announcements-v1";

export type AppStoreRelease = {
  version: string;
  whatsNew: string;
};

export type AppStoreConnectKey = {
  keyId: string;
  issuerId: string;
  privateKey: string;
};

export type AppStoreAnnounceEnv = {
  DISCORD_BOT_TOKEN?: string;
  ASC_KEY_ID?: string;
  ASC_ISSUER_ID?: string;
  ASC_PRIVATE_KEY?: string;
  STAR_HISTORY: {
    get(key: string): Promise<string | null>;
    put(key: string, value: string): Promise<unknown>;
  };
};

/** The Discord message for a live App Store release, within Discord's limit. */
export function appStoreAnnouncementText(release: AppStoreRelease): string {
  const max = 2000;
  const lead = `iOS ${release.version || "the latest version"} is on the App Store.`;
  const notes = release.whatsNew.trim();
  const heading = notes ? "\n\nWhat's new:\n" : "";
  const budget = max - lead.length - heading.length;
  const fitted = notes.length <= budget
    ? notes
    : `${notes.slice(0, Math.max(0, budget - 1)).trimEnd()}…`;
  const text = `${lead}${heading}${fitted}`;
  return text.length <= max ? text : text.slice(0, max);
}

/** The App Store Connect key from the Worker secrets; throws when any part is missing. */
export function appStoreConnectKey(env: AppStoreAnnounceEnv): AppStoreConnectKey {
  const keyId = (env.ASC_KEY_ID || "").trim();
  const issuerId = (env.ASC_ISSUER_ID || "").trim();
  const privateKey = (env.ASC_PRIVATE_KEY || "").trim();
  if (!keyId || !issuerId || !privateKey) throw new Error("appstore_connect_key_missing");
  return { keyId, issuerId, privateKey };
}

/** An ES256 App Store Connect API token, valid for ten minutes from `now`. */
export async function signAppStoreConnectJwt(key: AppStoreConnectKey, now: number): Promise<string> {
  const signingKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(key.privateKey),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
  const header = base64Url(JSON.stringify({ alg: "ES256", kid: key.keyId, typ: "JWT" }));
  const payload = base64Url(JSON.stringify({
    iss: key.issuerId,
    iat: now,
    exp: now + TOKEN_LIFETIME_SECONDS,
    aud: ASC_AUDIENCE,
  }));
  const unsigned = `${header}.${payload}`;
  // WebCrypto returns the raw r||s signature JWS expects for ES256.
  const signature = await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    signingKey,
    new TextEncoder().encode(unsigned),
  );
  return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
}

type RawLocalization = {
  type?: string;
  id?: string;
  attributes?: { locale?: string; whatsNew?: string | null };
};

type RawVersionsResponse = {
  data?: {
    id?: string;
    attributes?: { versionString?: string };
    relationships?: { appStoreVersionLocalizations?: { data?: { id?: string }[] } };
  }[];
  included?: RawLocalization[];
};

function versionsUrl(): string {
  const url = new URL(`/v1/apps/${APP_ID}/appStoreVersions`, ASC_API);
  url.searchParams.set("filter[platform]", "IOS");
  url.searchParams.set("filter[appStoreState]", "READY_FOR_SALE");
  url.searchParams.set("limit", "1");
  // The Customer Support role can read localizations only through an include.
  url.searchParams.set("include", "appStoreVersionLocalizations");
  url.searchParams.set("fields[appStoreVersions]", "versionString,appStoreVersionLocalizations");
  url.searchParams.set("fields[appStoreVersionLocalizations]", "locale,whatsNew");
  url.searchParams.set("limit[appStoreVersionLocalizations]", "50");
  return url.toString();
}

function englishNotes(localizations: RawLocalization[]): string {
  const english = localizations.find((item) => item.attributes?.locale === NOTES_LOCALE)
    ?? localizations.find((item) => item.attributes?.locale?.startsWith("en"));
  return (english?.attributes?.whatsNew || "").trim();
}

/**
 * The version currently downloadable from the App Store, or null when App
 * Store Connect reports none. Throws on transport, HTTP, or JSON failures so
 * the hourly job reports them.
 */
export async function fetchAppStoreRelease(
  key: AppStoreConnectKey,
  fetchImpl: typeof fetch,
  now = Math.floor(Date.now() / 1000),
): Promise<AppStoreRelease | null> {
  const token = await signAppStoreConnectJwt(key, now);
  let response: Response;
  try {
    response = await fetchImpl(versionsUrl(), {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
    });
  } catch {
    throw new Error("appstore_connect_request_failed");
  }
  if (!response.ok) throw new Error(`appstore_connect_failed_${response.status}`);

  let body: RawVersionsResponse;
  try {
    body = await response.json() as RawVersionsResponse;
  } catch {
    throw new Error(`appstore_connect_invalid_json_${response.status}`);
  }
  const live = body.data?.[0];
  const version = (live?.attributes?.versionString || "").trim();
  if (!version) return null;

  const linked = new Set(
    (live?.relationships?.appStoreVersionLocalizations?.data ?? []).map((item) => item.id),
  );
  const localizations = (body.included ?? []).filter((item) =>
    item.type === "appStoreVersionLocalizations" && (linked.size === 0 || linked.has(item.id))
  );
  return { version, whatsNew: englishNotes(localizations) };
}

async function readState(env: AppStoreAnnounceEnv): Promise<string | null> {
  const raw = await env.STAR_HISTORY.get(STATE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as { version?: unknown };
    return typeof parsed.version === "string" && parsed.version ? parsed.version : null;
  } catch {
    return null;
  }
}

async function writeState(env: AppStoreAnnounceEnv, version: string): Promise<void> {
  await env.STAR_HISTORY.put(STATE_KEY, JSON.stringify({ version }));
}

export async function announceIOSAppStoreRelease(
  env: AppStoreAnnounceEnv,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  const token = (env.DISCORD_BOT_TOKEN || "").trim();
  if (!token) return;

  const live = await fetchAppStoreRelease(appStoreConnectKey(env), fetchImpl);
  if (!live) return;

  const previous = await readState(env);
  if (!previous) {
    await writeState(env, live.version);
    return;
  }
  if (previous === live.version) return;

  await postAnnouncement(token, appStoreAnnouncementText(live), fetchImpl);
  await writeState(env, live.version);
}
