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

export function appStoreAnnouncementText(release: AppStoreRelease): string {
  const title = release.version || "the latest version";
  const lead = `iOS ${title} is on the App Store.`;
  const notes = release.whatsNew.trim();
  const whatsNew = notes ? `\n\nWhat's new:\n${notes}` : "";
  const link = release.url ? `\n\n${release.url}` : "";
  const text = `${lead}${whatsNew}${link}`;
  return text.length <= 2000 ? text : `${text.slice(0, 1997)}...`;
}

type RawLookupResult = {
  version?: string;
  releaseNotes?: string;
  trackViewUrl?: string;
};

/** The version currently downloadable from the App Store, or null. */
export async function fetchAppStoreRelease(
  fetchImpl: typeof fetch,
): Promise<AppStoreRelease | null> {
  try {
    const response = await fetchImpl(LOOKUP_URL, {
      headers: {
        "User-Agent": "fud-ai-appstore-announce/1.0",
        "Accept-Language": "en-US,en;q=0.9",
      },
    });
    if (!response.ok) return null;
    const body = await response.json() as { results?: RawLookupResult[] };
    const result = body.results?.[0];
    const version = (result?.version || "").trim();
    if (!version) return null;
    return {
      version,
      whatsNew: (result?.releaseNotes || "").trim(),
      url: (result?.trackViewUrl || "").trim(),
    };
  } catch {
    return null;
  }
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
