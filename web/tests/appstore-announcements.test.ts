import { beforeAll, describe, expect, it, vi } from "vitest";
import {
  appStoreAnnouncementText,
  appStoreConnectKey,
  announceIOSAppStoreRelease,
  fetchAppStoreRelease,
  signAppStoreConnectJwt,
  type AppStoreAnnounceEnv,
  type AppStoreConnectKey,
} from "../appstore-announcements";

let keyPair: CryptoKeyPair;
let ascKey: AppStoreConnectKey;

beforeAll(async () => {
  keyPair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]) as CryptoKeyPair;
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", keyPair.privateKey) as ArrayBuffer);
  let binary = "";
  for (const byte of der) binary += String.fromCharCode(byte);
  const body = btoa(binary).match(/.{1,64}/g)?.join("\n") ?? "";
  ascKey = {
    keyId: "TESTKEY123",
    issuerId: "00000000-0000-0000-0000-000000000000",
    privateKey: `-----BEGIN PRIVATE KEY-----\n${body}\n-----END PRIVATE KEY-----\n`,
  };
});

function memoryEnv(stored: string | null): AppStoreAnnounceEnv & { saved: string[] } {
  const saved: string[] = [];
  return {
    DISCORD_BOT_TOKEN: "bot-token",
    ASC_KEY_ID: ascKey.keyId,
    ASC_ISSUER_ID: ascKey.issuerId,
    ASC_PRIVATE_KEY: ascKey.privateKey,
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

function base64UrlDecode(part: string): Uint8Array {
  const binary = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function decodePart(part: string): Record<string, unknown> {
  return JSON.parse(new TextDecoder().decode(base64UrlDecode(part))) as Record<string, unknown>;
}

function versionsResponse(version: string | null, notes = "Notes."): Response {
  if (!version) return Response.json({ data: [], included: [] });
  return Response.json({
    data: [{
      type: "appStoreVersions",
      id: "version-1",
      attributes: { versionString: version },
      relationships: { appStoreVersionLocalizations: { data: [{ type: "appStoreVersionLocalizations", id: "loc-fr" }, { type: "appStoreVersionLocalizations", id: "loc-en" }] } },
    }],
    included: [
      { type: "appStoreVersionLocalizations", id: "loc-fr", attributes: { locale: "fr-FR", whatsNew: "Notes en français." } },
      { type: "appStoreVersionLocalizations", id: "loc-en", attributes: { locale: "en-US", whatsNew: notes } },
    ],
  });
}

describe("app store announcements", () => {
  it("formats the version and notes", () => {
    const text = appStoreAnnouncementText({
      version: "7.1.1",
      whatsNew: "Removed the beta signup.",
    });
    expect(text).toContain("iOS 7.1.1 is on the App Store.");
    expect(text).toContain("What's new:\nRemoved the beta signup.");
  });

  it("signs a ten-minute ES256 App Store Connect token", async () => {
    const jwt = await signAppStoreConnectJwt(ascKey, 1_000);
    const [header = "", payload = "", signature = ""] = jwt.split(".");

    expect(decodePart(header)).toEqual({ alg: "ES256", kid: "TESTKEY123", typ: "JWT" });
    expect(decodePart(payload)).toEqual({
      iss: "00000000-0000-0000-0000-000000000000",
      iat: 1_000,
      exp: 1_600,
      aud: "appstoreconnect-v1",
    });
    const valid = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      keyPair.publicKey,
      base64UrlDecode(signature),
      new TextEncoder().encode(`${header}.${payload}`),
    );
    expect(valid).toBe(true);
  });

  it("asks App Store Connect for the live iOS version with a bearer token", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = requestUrl(input);
      expect(url.hostname).toBe("api.appstoreconnect.apple.com");
      expect(url.pathname).toBe("/v1/apps/6758935726/appStoreVersions");
      expect(url.searchParams.get("filter[platform]")).toBe("IOS");
      expect(url.searchParams.get("filter[appStoreState]")).toBe("READY_FOR_SALE");
      expect(url.searchParams.get("include")).toBe("appStoreVersionLocalizations");
      expect(url.search).not.toContain("eyJ");
      expect(new Headers(init?.headers).get("authorization")).toMatch(/^Bearer eyJ/);
      return versionsResponse("8.0", "Live notes.");
    });

    const release = await fetchAppStoreRelease(ascKey, fetchImpl as typeof fetch);
    expect(release).toEqual({ version: "8.0", whatsNew: "Live notes." });
  });

  it("returns null when no version is on sale", async () => {
    const fetchImpl = vi.fn(async () => versionsResponse(null));
    await expect(fetchAppStoreRelease(ascKey, fetchImpl as typeof fetch)).resolves.toBeNull();
  });

  it("records the live version on the first run without announcing", async () => {
    const env = memoryEnv(null);
    const fetchImpl = vi.fn(async () => versionsResponse("6.1"));

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([JSON.stringify({ version: "6.1" })]);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("announces once when the live version changes", async () => {
    const env = memoryEnv(JSON.stringify({ version: "7.1.1" }));
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      const url = requestUrl(input);
      if (url.hostname === "api.appstoreconnect.apple.com") return versionsResponse("8.0", "Fud AI 8.0 notes.");
      if (url.hostname === "discord.com") return new Response(null, { status: 200 });
      return new Response(null, { status: 404 });
    });

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);

    const discordCall = fetchImpl.mock.calls.find(([url]) => requestUrl(url).hostname === "discord.com");
    expect(discordCall).toBeDefined();
    expect(requestUrl(discordCall?.[0] ?? "https://example.com").pathname).toBe(
      `/api/v10/channels/1548481417728495678/messages`,
    );
    const sent = JSON.parse(String(discordCall?.[1]?.body)) as { content: string };
    expect(sent.content).toBe("iOS 8.0 is on the App Store.\n\nWhat's new:\nFud AI 8.0 notes.");
    expect(env.saved.at(-1)).toBe(JSON.stringify({ version: "8.0" }));
  });

  it("does not announce when the live version is unchanged", async () => {
    const env = memoryEnv(JSON.stringify({ version: "8.0" }));
    const fetchImpl = vi.fn(async () => versionsResponse("8.0"));

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);
    expect(env.saved).toEqual([]);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("rejects when App Store Connect fails so the job is reported", async () => {
    const env = memoryEnv(JSON.stringify({ version: "7.1.1" }));
    const fetchImpl = vi.fn(async () => new Response(null, { status: 401 }));

    await expect(announceIOSAppStoreRelease(env, fetchImpl as typeof fetch)).rejects.toThrow(
      "appstore_connect_failed_401",
    );
    expect(env.saved).toEqual([]);
  });

  it("rejects a non-JSON body with its status", async () => {
    const fetchImpl = vi.fn(async () => new Response("<html>blocked</html>", { status: 200 }));
    await expect(fetchAppStoreRelease(ascKey, fetchImpl as typeof fetch)).rejects.toThrow(
      "appstore_connect_invalid_json_200",
    );
  });

  it("fails clearly when the App Store Connect key is not configured", async () => {
    const env = { ...memoryEnv(JSON.stringify({ version: "7.1.1" })), ASC_PRIVATE_KEY: "" };
    const fetchImpl = vi.fn();

    await expect(announceIOSAppStoreRelease(env, fetchImpl as typeof fetch)).rejects.toThrow(
      "appstore_connect_key_missing",
    );
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(() => appStoreConnectKey({ ...env, ASC_PRIVATE_KEY: " k ", ASC_KEY_ID: "" })).toThrow(
      "appstore_connect_key_missing",
    );
  });

  it("skips quietly without a Discord bot token", async () => {
    const env = { ...memoryEnv(JSON.stringify({ version: "7.1.1" })), DISCORD_BOT_TOKEN: "" };
    const fetchImpl = vi.fn();

    await announceIOSAppStoreRelease(env, fetchImpl as typeof fetch);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("clips long notes within Discord's limit", () => {
    const text = appStoreAnnouncementText({
      version: "7.1.1",
      whatsNew: "N".repeat(2500),
    });
    expect(text.length).toBeLessThanOrEqual(2000);
    expect(text.startsWith("iOS 7.1.1 is on the App Store.")).toBe(true);
  });
});
