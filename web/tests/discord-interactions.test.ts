import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../worker";
import {
  ANDROID_BUG_CHANNEL_ID,
  DISCORD_INTERACTIONS_PATH,
  handleDiscordInteractionsRequest,
  IOS_BUG_CHANNEL_ID,
  labelsForBugPlatform,
  resolveBugPlatform,
  verifyDiscordSignature,
} from "../discord-interactions";

const APP_ID = "1548469419922038845";
const GUILD_ID = "1548469034570354709";
const ISSUE_URL = "https://github.com/apoorvdarshan/fud-ai/issues/42";

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function ed25519Pair(): Promise<{ privateKey: CryptoKey; publicKeyHex: string }> {
  const pair = (await crypto.subtle.generateKey(
    { name: "Ed25519" },
    true,
    ["sign", "verify"],
  )) as CryptoKeyPair;
  const raw = await crypto.subtle.exportKey("raw", pair.publicKey);
  return { privateKey: pair.privateKey, publicKeyHex: bytesToHex(new Uint8Array(raw as ArrayBuffer)) };
}

type MockFetch = (input: string, init?: RequestInit) => Promise<Response>;

function requestBody(init: RequestInit | undefined): string {
  return String(init?.body ?? "");
}

async function signedRequest(
  privateKey: CryptoKey,
  body: unknown,
): Promise<Request> {
  const timestamp = "1710000000";
  const text = JSON.stringify(body);
  const message = new TextEncoder().encode(timestamp + text);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, privateKey, message));
  return new Request(`https://fud-ai.app${DISCORD_INTERACTIONS_PATH}`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "X-Signature-Ed25519": bytesToHex(sig),
      "X-Signature-Timestamp": timestamp,
    },
    body: text,
  });
}

function env(publicKeyHex: string, extra: Record<string, string> = {}) {
  return {
    DISCORD_PUBLIC_KEY: publicKeyHex,
    DISCORD_APPLICATION_ID: APP_ID,
    DISCORD_GEMINI_API_KEY: "test-gemini-key",
    GITHUB_TOKEN: "test-github-token",
    ...extra,
  };
}

function waiters() {
  const pending: Promise<unknown>[] = [];
  return {
    ctx: { waitUntil: (promise: Promise<unknown>) => pending.push(promise) },
    flush: () => Promise.all(pending),
  };
}

function command(name: string, options: Array<{ name: string; value: string }>, extra: Record<string, unknown> = {}) {
  return {
    type: 2,
    token: "interaction-token",
    id: "interaction-id",
    application_id: APP_ID,
    guild_id: GUILD_ID,
    channel_id: "999",
    data: { name, options: options.map((option) => ({ type: 3, ...option })) },
    member: { user: { id: "user-1", username: "reporter", global_name: "Reporter" } },
    ...extra,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("discord interactions", () => {
  it("exports the interactions path", () => {
    expect(DISCORD_INTERACTIONS_PATH).toBe("/api/discord/interactions");
  });

  it("rejects garbage signatures", async () => {
    const ok = await verifyDiscordSignature(
      "{}",
      "00".repeat(64),
      "0",
      "0ce6e6014282b489ce0930fad550e978c7d623cc71040d14250223110a9a9f57",
    );
    expect(ok).toBe(false);
  });

  it("infers platform from the iOS and Android channels when omitted", () => {
    expect(resolveBugPlatform("", IOS_BUG_CHANNEL_ID)).toBe("iOS");
    expect(resolveBugPlatform("", ANDROID_BUG_CHANNEL_ID)).toBe("Android");
    expect(resolveBugPlatform("Android", IOS_BUG_CHANNEL_ID)).toBe("Android");
    expect(resolveBugPlatform("ios", "other")).toBe("iOS");
    expect(resolveBugPlatform("", "other")).toBe("");
    expect(labelsForBugPlatform("iOS")).toEqual(["bug", "ios"]);
    expect(labelsForBugPlatform("Android")).toEqual(["bug", "android"]);
    expect(labelsForBugPlatform("")).toEqual(["bug"]);
  });

  it("answers Discord PINGs", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const response = await handleDiscordInteractionsRequest(
      await signedRequest(privateKey, { type: 1 }),
      env(publicKeyHex),
    );
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ type: 1 });
  });

  it("defers /ask and follows up with the Gemini reply", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("generativelanguage")) {
        return Response.json({
          candidates: [{ content: { parts: [{ text: "Add your key in Settings → AI Access." }] } }],
        });
      }
      if (String(url).includes("discord.com")) return new Response(null, { status: 200 });
      throw new Error(`unexpected fetch ${url}`);
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();

    const response = await handleDiscordInteractionsRequest(
      await signedRequest(
        privateKey,
        command("ask", [{ name: "question", value: "How do I add my Gemini key?" }]),
      ),
      env(publicKeyHex),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();

    expect(fetch.mock.calls.some(([url]) => String(url).includes("generativelanguage"))).toBe(true);
    expect(fetch.mock.calls.some(([url]) => String(url).includes("api.github.com"))).toBe(false);
    const discordCall = fetch.mock.calls.find(([url]) => String(url).includes("discord.com/api/v10/webhooks"));
    expect(discordCall?.[1]).toEqual({
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ content: "Add your key in Settings → AI Access." }),
    });
  });

  it("defers /bug, files a labeled GitHub issue, and replies with the URL", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("api.github.com/repos/apoorvdarshan/fud-ai/issues")) {
        return Response.json({ html_url: ISSUE_URL, number: 42 }, { status: 201 });
      }
      if (String(url).includes("discord.com")) return new Response(null, { status: 200 });
      throw new Error(`unexpected fetch ${url}`);
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();

    const response = await handleDiscordInteractionsRequest(
      await signedRequest(
        privateKey,
        command(
          "bug",
          [
            { name: "title", value: "Crash on save" },
            { name: "details", value: "Tap save after logging a meal." },
            { name: "device", value: "iPhone 15" },
            { name: "app_version", value: "7.1 (38)" },
            { name: "platform", value: "iOS" },
          ],
          { channel_id: ANDROID_BUG_CHANNEL_ID },
        ),
      ),
      env(publicKeyHex),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();

    const githubCall = fetch.mock.calls.find(([url]) => String(url).includes("api.github.com"));
    expect(githubCall?.[0]).toBe("https://api.github.com/repos/apoorvdarshan/fud-ai/issues");
    expect(githubCall?.[1]).toMatchObject({
      method: "POST",
      headers: expect.objectContaining({
        Authorization: "Bearer test-github-token",
      }),
    });
    const created = JSON.parse(requestBody(githubCall?.[1])) as {
      title: string;
      body: string;
      labels: string[];
    };
    expect(created.title).toBe("Crash on save");
    expect(created.labels).toEqual(["bug", "ios"]);
    expect(created.body).toContain("Tap save after logging a meal.");
    expect(created.body).toContain("iPhone 15");
    expect(created.body).toContain("7.1 (38)");
    expect(created.body).toContain("iOS");
    expect(created.body).toContain("Reporter");
    expect(created.body).toContain("`user-1`");
    expect(created.body).toContain(ANDROID_BUG_CHANNEL_ID);
    expect(created.body).toContain(GUILD_ID);

    const discordCall = fetch.mock.calls.find(([url]) => String(url).includes("discord.com/api/v10/webhooks"));
    expect(requestBody(discordCall?.[1])).toBe(JSON.stringify({ content: `Opened ${ISSUE_URL}` }));
  });

  it("infers Android from the Android channel when platform is omitted", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("api.github.com")) {
        return Response.json({ html_url: ISSUE_URL, number: 42 }, { status: 201 });
      }
      return new Response(null, { status: 200 });
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();

    const response = await handleDiscordInteractionsRequest(
      await signedRequest(
        privateKey,
        command(
          "bug",
          [
            { name: "title", value: "Dark mode flicker" },
            { name: "details", value: "Theme flips on resume." },
          ],
          { channel_id: ANDROID_BUG_CHANNEL_ID },
        ),
      ),
      env(publicKeyHex),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();
    const created = JSON.parse(requestBody(fetch.mock.calls[0]?.[1])) as { labels: string[] };
    expect(created.labels).toEqual(["bug", "android"]);
  });

  it("retries with only the bug label when ios/android labels are missing on GitHub", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("api.github.com")) {
        if (fetch.mock.calls.filter(([called]) => String(called).includes("api.github.com")).length === 1) {
          return Response.json({ message: "Validation Failed" }, { status: 422 });
        }
        return Response.json({ html_url: ISSUE_URL, number: 42 }, { status: 201 });
      }
      return new Response(null, { status: 200 });
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();

    await handleDiscordInteractionsRequest(
      await signedRequest(
        privateKey,
        command(
          "bug",
          [
            { name: "title", value: "Crash on save" },
            { name: "details", value: "Steps" },
          ],
          { channel_id: IOS_BUG_CHANNEL_ID },
        ),
      ),
      env(publicKeyHex),
      ctx,
    );
    await flush();

    const githubBodies = fetch.mock.calls
      .filter(([url]) => String(url).includes("api.github.com"))
      .map(([, options]) => JSON.parse(requestBody(options)) as { labels: string[] });
    expect(githubBodies.map((body) => body.labels)).toEqual([["bug", "ios"], ["bug"]]);
  });

  it("follows up when GITHUB_TOKEN is missing", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("discord.com")) return new Response(null, { status: 200 });
      throw new Error(`unexpected fetch ${url}`);
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();

    const response = await handleDiscordInteractionsRequest(
      await signedRequest(
        privateKey,
        command("bug", [
          { name: "title", value: "Crash on save" },
          { name: "details", value: "Steps" },
        ]),
      ),
      env(publicKeyHex, { GITHUB_TOKEN: "" }),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();
    expect(fetch.mock.calls.some(([url]) => String(url).includes("api.github.com"))).toBe(false);
    expect(requestBody(fetch.mock.calls[0]?.[1])).toContain("GitHub isn’t configured");
  });

  it("rejects /bug without title or details", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const response = await handleDiscordInteractionsRequest(
      await signedRequest(privateKey, command("bug", [{ name: "title", value: "Missing details" }])),
      env(publicKeyHex),
    );
    expect(await response.json()).toMatchObject({
      type: 4,
      data: { flags: 64 },
    });
  });

  it("routes the live interactions path on the Worker", async () => {
    const response = await worker.fetch(
      new Request(`https://fud-ai.app${DISCORD_INTERACTIONS_PATH}`, { method: "GET" }),
      { DISCORD_PUBLIC_KEY: "abc", GITHUB_TOKEN: "token" } as unknown as Env,
    );
    expect(response.status).toBe(405);
  });
});
