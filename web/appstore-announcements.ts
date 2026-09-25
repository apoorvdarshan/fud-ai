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

export type AppStoreRelease = {
  version: string;
  whatsNew: string;
  url: string;
};

export type AppStoreAnnounceEnv = {
  DISCORD_BOT_TOKEN?: string;
  STAR_HISTORY: {
    get(key: string): Promise<string | null>;
    put(key: string, value: string): Promise<unknown>;
  };
};

/** The Discord message for a live App Store release; keeps the link within Discord's limit. */
export function appStoreAnnouncementText(release: AppStoreRelease): string {
  const lead = `iOS ${release.version || "the latest version"} is on the App Store.`;
  const link = release.url ? `\n\n${release.url}` : "";
  const notes = release.whatsNew.trim();
  const heading = notes ? "\n\nWhat's new:\n" : "";
  const budget = 2000 - lead.length - heading.length - link.length;
  const fitted = notes.length <= budget
    ? notes
    : `${notes.slice(0, Math.max(0, budget - 1)).trimEnd()}…`;
  const text = `${lead}${heading}${fitted}${link}`;
  return text.length <= 2000 ? text : text.slice(0, 2000);
}

type RawLookupResult = {
  version?: string;
  releaseNotes?: string;
  trackViewUrl?: string;
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
        "User-Agent": "fud-ai-appstore-announce/1.0",
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
    throw new Error("appstore_lookup_invalid_json");
  }
  const result = body.results?.[0];
  const version = (result?.version || "").trim();
  if (!version) return null;
  return {
    version,
    whatsNew: (result?.releaseNotes || "").trim(),
    url: (result?.trackViewUrl || "").trim(),
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
