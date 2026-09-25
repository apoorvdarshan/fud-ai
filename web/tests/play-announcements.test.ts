import { describe, expect, it, vi } from "vitest";
import {
  ANNOUNCEMENTS_CHANNEL_ID,
  announcementText,
  announceAndroidPlayReleases,
  releasesToAnnounce,
  type PlayAnnounceEnv,
  type PlayRelease,
} from "../play-announcements";

const beta: PlayRelease = {
  track: "beta",
  versionCode: "39",
  name: "7.2",
  whatsNew: "Weekly Challenge pages.",
};
const production: PlayRelease = {
  track: "production",
  versionCode: "38",
  name: "7.1",
  whatsNew: "Play Store notes.",
};

async function serviceAccountJson(): Promise<string> {
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign"],
  ) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey) as ArrayBuffer);
  let binary = "";
  for (const byte of pkcs8) binary += String.fromCharCode(byte);
  const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(binary)}\n-----END PRIVATE KEY-----\n`;
  return JSON.stringify({ client_email: "play@example.com", private_key: pem });
}

function memoryEnv(stored: string | null, serviceAccount: string): PlayAnnounceEnv & { saved: string[] } {
  const saved: string[] = [];
  return {
    DISCORD_BOT_TOKEN: "bot-token",
    PLAY_SERVICE_ACCOUNT_JSON: serviceAccount,
    saved,
    STAR_HISTORY: {
      get: vi.fn().mockResolvedValue(stored),
      put: vi.fn(async (_key: string, value: string) => {
        saved.push(value);
      }),
    },
  };
}

function requestUrl(input: RequestInfo | URL): URL {
  if (typeof input === "string") return new URL(input);
  if (input instanceof URL) return input;
  return new URL(input.url);
}

describe("play announcements", () => {
  it("stays quiet the first time it sees the current tracks", () => {
    expect(releasesToAnnounce(null, [beta, production])).toEqual([]);
  });

  it("announces only a track whose version code changed", () => {
    expect(releasesToAnnounce({ beta: "38", production: "38" }, [beta, production])).toEqual([beta]);
  });

  it("includes what's new and keeps bugs in the beta channel", () => {
    const text = announcementText(beta);
    expect(text).toContain("Android 7.2 (39) is ready for testers.");
    expect(text).toContain("What's new:\nWeekly Challenge pages.");
    expect(text).toContain("#beta-android");
    expect(announcementText(production)).toContain("is on the Play Store.");
    expect(announcementText(production)).not.toContain("#beta-android");
  });

  it("posts a new open-testing build only to announcements", async () => {
    const env = memoryEnv(JSON.stringify({ beta: "38", production: "38" }), await serviceAccountJson());
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = requestUrl(input);
      if (url.hostname === "oauth2.googleapis.com" && url.pathname === "/token") {
        return Response.json({ access_token: "play-token" });
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/edits")) {
        return Response.json({ id: "edit-1" });
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/tracks")) {
        return Response.json({
          tracks: [
            { track: "beta", releases: [{ name: "7.2", status: "completed", versionCodes: ["39"], releaseNotes: [{ language: "en-US", text: "Weekly Challenge pages." }] }] },
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"], releaseNotes: [{ language: "en-US", text: "Play Store notes." }] }] },
          ],
        });
      }
      if (url.hostname === "discord.com" && url.pathname === `/api/v10/channels/${ANNOUNCEMENTS_CHANNEL_ID}/messages`) {
        return new Response(null, { status: 200 });
      }
      return new Response(null, { status: 404 });
    });

    await announceAndroidPlayReleases(env, fetchImpl as typeof fetch);

    const discordCall = fetchImpl.mock.calls.find(([url]) => requestUrl(url).hostname === "discord.com");
    expect(requestUrl(discordCall?.[0] ?? "https://example.com").pathname).toBe(
      `/api/v10/channels/${ANNOUNCEMENTS_CHANNEL_ID}/messages`,
    );
    expect(env.saved.at(-1)).toBe(JSON.stringify({ beta: "39", production: "38" }));
  });

  it("records the current tracks without posting when nothing was stored", async () => {
    const env = memoryEnv(null, await serviceAccountJson());
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = requestUrl(input);
      if (url.hostname === "oauth2.googleapis.com" && url.pathname === "/token") {
        return Response.json({ access_token: "play-token" });
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/edits")) {
        return Response.json({ id: "edit-1" });
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/tracks")) {
        return Response.json({
          tracks: [
            { track: "beta", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"] }] },
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"] }] },
          ],
        });
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/edits/edit-1")) {
        return new Response(null, { status: 200 });
      }
      throw new Error(`unexpected ${url.pathname}`);
    });

    await announceAndroidPlayReleases(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([JSON.stringify({ beta: "38", production: "38" })]);
  });
});
