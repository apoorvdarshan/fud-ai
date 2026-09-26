/**
 * Hourly App Store check. Posts once in #announcements when a new iOS version
 * becomes the live App Store version, with the App Store "What's new" notes.
 *
 * Unlike the Play track API (which reports a release as "completed" while
 * Google is still reviewing), the iTunes lookup API only ever returns the
 * version users can actually download, so it is already a liveness signal.
 */

import { postAnnouncement } from "./discord-announce";

const BUNDLE_ID = "com.apoorvdarshan.calorietracker";
const LOOKUP_URL = `https://itunes.apple.com/lookup?bundleId=${BUNDLE_ID}&country=us`;
const STATE_KEY = "ios-appstore-announcements-v1";
// Apple often answers a bot user agent with an HTML block page. A browser
// header is what the lookup accepts from Cloudflare.
const LOOKUP_USER_AGENT =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15";

export type AppStoreRelease = {
  version: string;
  whatsNew: string;
};

export type AppStoreAnnounceEnv = {
  DISCORD_BOT_TOKEN?: string;
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

type RawLookupResult = {
  version?: string;
  releaseNotes?: string;
};

/**
 * The version currently downloadable from the App Store, or null when the
 * lookup is valid but carries no release. Throws on transport, HTTP, or JSON
 * failures so the hourly job reports them.
 */
export async function fetchAppStoreRelease(
  fetchImpl: typeof fetch,
): Promise<AppStoreRelease | null> {
  let response: Response;
  try {
    response = await fetchImpl(LOOKUP_URL, {
      headers: {
        "User-Agent": LOOKUP_USER_AGENT,
        Accept: "application/json",
        "Accept-Language": "en-US,en;q=0.9",
      },
    });
  } catch {
    throw new Error("appstore_lookup_request_failed");
  }
  if (!response.ok) throw new Error(`appstore_lookup_failed_${response.status}`);

  let body: { results?: RawLookupResult[] };
  try {
    body = await response.json() as { results?: RawLookupResult[] };
  } catch {
    const contentType = (response.headers.get("content-type") || "none").split(";")[0]?.trim() || "none";
    throw new Error(`appstore_lookup_invalid_json_${response.status}_${contentType}`);
  }
  const result = body.results?.[0];
  const version = (result?.version || "").trim();
  if (!version) return null;
  return {
    version,
    whatsNew: (result?.releaseNotes || "").trim(),
  };
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

  const live = await fetchAppStoreRelease(fetchImpl);
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
