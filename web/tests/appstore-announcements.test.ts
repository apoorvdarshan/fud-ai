import { describe, expect, it, vi } from "vitest";
import {
  appStoreAnnouncementText,
  announceIOSAppStoreRelease,
  fetchAppStoreRelease,
  type AppStoreAnnounceEnv,
} from "../appstore-announcements";

function memoryEnv(stored: string | null): AppStoreAnnounceEnv & { saved: string[] } {
  const saved: string[] = [];
  return {
    DISCORD_BOT_TOKEN: "bot-token",
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

function lookupResponse(version: string, notes = "Notes."): Response {
  return Response.json({
    resultCount: 1,
    results: [{ version, releaseNotes: notes, trackViewUrl: "https://apps.apple.com/app/id1" }],
  });
}

describe("app store announcements", () => {
  it("formats the version, notes, and link", () => {
    const text = appStoreAnnouncementText({
      version: "7.1.1",
      whatsNew: "Removed the beta signup.",
      url: "https://apps.apple.com/app/id1",
    });
    expect(text).toContain("iOS 7.1.1 is on the App Store.");
    expect(text).toContain("What's new:\nRemoved the beta signup.");
    expect(text).toContain("https://apps.apple.com/app/id1");
  });

  it("records the live version on the first run without announcing", async () => {
    const env = memoryEnv(null);
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      expect(requestUrl(input).hostname).toBe("itunes.apple.com");
      return lookupResponse("6.1");
    });

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([JSON.stringify({ version: "6.1" })]);
  });

  it("announces once when the live version changes", async () => {
    const env = memoryEnv(JSON.stringify({ version: "6.1" }));
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = requestUrl(input);
      if (url.hostname === "itunes.apple.com") return lookupResponse("7.1.1", "Fud AI 7.1.1 notes.");
      if (url.hostname === "discord.com") return new Response(null, { status: 200 });
      return new Response(null, { status: 404 });
    });

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);

    const discordCall = fetchImpl.mock.calls.find(([url]) => requestUrl(url).hostname === "discord.com");
    expect(discordCall).toBeDefined();
    expect(requestUrl(discordCall?.[0] ?? "https://example.com").pathname).toBe(
      `/api/v10/channels/1548481417728495678/messages`,
    );
    expect(env.saved.at(-1)).toBe(JSON.stringify({ version: "7.1.1" }));
  });

  it("does not announce when the live version is unchanged", async () => {
    const env = memoryEnv(JSON.stringify({ version: "7.1.1" }));
    const fetchImpl = vi.fn(async () => lookupResponse("7.1.1"));

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([]);
  });

  it("rejects when the lookup fails so the job is reported", async () => {
    const env = memoryEnv(JSON.stringify({ version: "6.1" }));
    const fetchImpl = vi.fn(async () => new Response(null, { status: 503 }));

    await expect(announceIOSAppStoreRelease(env, fetchImpl as typeof fetch)).rejects.toThrow(
      "appstore_lookup_failed_503",
    );
    expect(env.saved).toEqual([]);
  });

  it("keeps the App Store link when the notes are long", () => {
    const url = "https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726?uo=4";
    const text = appStoreAnnouncementText({
      version: "7.1.1",
      whatsNew: "N".repeat(2500),
      url,
    });
    expect(text.length).toBeLessThanOrEqual(2000);
    expect(text.endsWith(url)).toBe(true);
  });

  it("keeps the App Store link even with oversized metadata", () => {
    const url = "https://apps.apple.com/us/app/fud-ai-calorie-tracker/id6758935726?uo=4";
    const text = appStoreAnnouncementText({
      version: "9".repeat(3000),
      whatsNew: "N".repeat(3000),
      url,
    });
    expect(text.length).toBeLessThanOrEqual(2000);
    expect(text.endsWith(url)).toBe(true);
  });

  it("reads the released version from the lookup payload", async () => {
    const fetchImpl = vi.fn(async () => lookupResponse("7.1.1", "Live notes."));
    const release = await fetchAppStoreRelease(fetchImpl as typeof fetch);
    expect(release).toEqual({
      version: "7.1.1",
      whatsNew: "Live notes.",
      url: "https://apps.apple.com/app/id1",
    });
  });
});
