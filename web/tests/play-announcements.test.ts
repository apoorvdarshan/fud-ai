import { describe, expect, it, vi } from "vitest";
import {
  ANNOUNCEMENTS_CHANNEL_ID,
  announcementText,
  announceAndroidPlayReleases,
  playStoreShowsRelease,
  releasesToAnnounce,
  type PlayAnnounceEnv,
  type PlayRelease,
} from "../play-announcements";

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
    expect(releasesToAnnounce(null, [production])).toEqual([]);
  });

  it("does not announce a track that has no version code", () => {
    expect(releasesToAnnounce(
      { production: "38" },
      [{ ...production, versionCode: "" }],
    )).toEqual([]);
  });

  it("announces only a track whose version code changed", () => {
    expect(releasesToAnnounce(
      { production: "37" },
      [production],
    )).toEqual([production]);
  });

  it("includes what's new for production", () => {
    const text = announcementText(production);
    expect(text).toContain("Android 7.1 (38) is on the Play Store.");
    expect(text).toContain("What's new:\nPlay Store notes.");
    expect(text).not.toContain("#beta-android");
  });

  it("posts a new production build only to announcements", async () => {
    const env = memoryEnv(JSON.stringify({ production: "37" }), await serviceAccountJson());
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
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"], releaseNotes: [{ language: "en-US", text: "Play Store notes." }] }] },
          ],
        });
      }
      if (url.hostname === "play.google.com") {
        return new Response(
          "<html>What's new Play Store notes. flag Flag as inappropriate</html>",
          { status: 200 },
        );
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
    expect(env.saved.at(-1)).toBe(JSON.stringify({ production: "38" }));
  });

  it("waits while the Play listing still shows the old version", async () => {
    const env = memoryEnv(JSON.stringify({ production: "37" }), await serviceAccountJson());
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
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"], releaseNotes: [{ language: "en-US", text: "Play Store notes." }] }] },
          ],
        });
      }
      if (url.hostname === "play.google.com") {
        return new Response(
          "<html>What's new Fud AI 7.0.1 • Older notes. flag Flag as inappropriate</html>",
          { status: 200 },
        );
      }
      if (url.hostname === "discord.com") {
        throw new Error("should not announce while in review");
      }
      return new Response(null, { status: 404 });
    });

    await announceAndroidPlayReleases(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([]);
  });

  it("matches notes, and only falls back to the name without notes", () => {
    const live = "<html>What’s new Fud AI 7.1.1 • Removed the beta signup. flag Flag as inappropriate</html>";
    expect(playStoreShowsRelease(live, { track: "production", versionCode: "39", name: "7.1.1", whatsNew: "Fud AI 7.1.1 • Removed the beta signup." })).toBe(true);
    // A reused name must not identify a different build whose notes do not match.
    expect(playStoreShowsRelease(live, { track: "production", versionCode: "39", name: "7.1.1", whatsNew: "Something totally different." })).toBe(false);
    // Without notes, the name is the only signal available.
    expect(playStoreShowsRelease(live, { track: "production", versionCode: "39", name: "7.1.1", whatsNew: "" })).toBe(true);
    expect(playStoreShowsRelease(live, { track: "production", versionCode: "39", name: "7.2", whatsNew: "" })).toBe(false);
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
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"], releaseNotes: [{ language: "en-US", text: "Play Store notes." }] }] },
          ],
        });
      }
      if (url.hostname === "play.google.com") {
        return new Response(
          "<html>What's new Play Store notes. flag Flag as inappropriate</html>",
          { status: 200 },
        );
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/edits/edit-1")) {
        return new Response(null, { status: 200 });
      }
      throw new Error(`unexpected ${url.pathname}`);
    });

    await announceAndroidPlayReleases(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([JSON.stringify({ production: "38" })]);
  });

  it("leaves a first-run release pending while the listing shows an older one", async () => {
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
            { track: "production", releases: [{ name: "7.1.1", status: "completed", versionCodes: ["39"], releaseNotes: [{ language: "en-US", text: "Fud AI 7.1.1 notes." }] }] },
          ],
        });
      }
      if (url.hostname === "play.google.com") {
        return new Response(
          "<html>What's new Fud AI 7.1 • Old notes. flag Flag as inappropriate</html>",
          { status: 200 },
        );
      }
      if (url.hostname === "androidpublisher.googleapis.com" && url.pathname.endsWith("/edits/edit-1")) {
        return new Response(null, { status: 200 });
      }
      throw new Error(`unexpected ${url.pathname}`);
    });

    await announceAndroidPlayReleases(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([JSON.stringify({ production: "" })]);
  });

  it("rejects when the public listing cannot be read", async () => {
    const env = memoryEnv(JSON.stringify({ production: "37" }), await serviceAccountJson());
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
            { track: "production", releases: [{ name: "7.1", status: "completed", versionCodes: ["38"], releaseNotes: [{ language: "en-US", text: "Play Store notes." }] }] },
          ],
        });
      }
      if (url.hostname === "play.google.com") return new Response(null, { status: 503 });
      return new Response(null, { status: 404 });
    });

    await expect(announceAndroidPlayReleases(env, fetchImpl as typeof fetch)).rejects.toThrow("play_listing_failed_503");
    expect(env.saved).toEqual([]);
  });
});
