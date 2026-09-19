import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../worker";
import {
  ANDROID_BUG_CHANNEL_ID,
  deriveIssueTitle,
  DISCORD_INTERACTIONS_PATH,
  FEATURE_ISSUE_LABEL,
  handleDiscordInteractionsRequest,
  IOS_BUG_CHANNEL_ID,
  labelsForBugPlatform,
  labelsForFeatureRequest,
  resolveBugPlatform,
  resolveFeaturePlatform,
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

  it("does not infer /feature platform from Discord channels", () => {
    expect(resolveFeaturePlatform("")).toBe("");
    expect(resolveFeaturePlatform("iOS")).toBe("iOS");
    expect(resolveFeaturePlatform("android")).toBe("Android");
    expect(resolveFeaturePlatform("both")).toBe("both");
    expect(resolveFeaturePlatform("none")).toBe("");
    expect(labelsForFeatureRequest()).toEqual([FEATURE_ISSUE_LABEL]);
    expect(FEATURE_ISSUE_LABEL).toBe("enhancement");
  });

  it("derives a GitHub title from freeform report text", () => {
    expect(deriveIssueTitle("Crash on save")).toBe("Crash on save");
    expect(deriveIssueTitle("  Crash on save  \n\nTap save after logging a meal.")).toBe(
      "Crash on save",
    );
    expect(deriveIssueTitle("\n\nWidget calories\nShow leftover calories.")).toBe("Widget calories");
    expect(deriveIssueTitle("")).toBe("Discord report");

    const longLine =
      "This first line is deliberately longer than one hundred characters so it cannot be used as the issue title";
    expect(longLine.length).toBeGreaterThan(100);
    const clipped = deriveIssueTitle(`${longLine}\nMore details after the break.`);
    expect(clipped.endsWith("…")).toBe(true);
    expect(clipped.length).toBeLessThanOrEqual(101);
    expect(clipped.includes("\n")).toBe(false);
    expect(clipped.startsWith("This first line is deliberately")).toBe(true);
    expect(clipped).not.toContain("More details");

    const oneWord = "x".repeat(140);
    expect(deriveIssueTitle(oneWord)).toBe(`${"x".repeat(100)}…`);
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
            {
              name: "report",
              value: "Crash on save\n\nTap save after logging a meal.\niPhone 15, 7.1 (38)",
            },
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
    expect(created.body).toContain("Crash on save");
    expect(created.body).toContain("Tap save after logging a meal.");
    expect(created.body).toContain("iPhone 15, 7.1 (38)");
    expect(created.body).toContain("**Platform:** iOS");
    expect(created.body).toContain("Reported via Discord `/bug`");
    expect(created.body).toContain("Reporter");
    expect(created.body).toContain("`user-1`");
    expect(created.body).toContain(ANDROID_BUG_CHANNEL_ID);
    expect(created.body).toContain(GUILD_ID);
    expect(created.body).not.toContain("## Details");
    expect(created.body).not.toContain("## Device");

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
          [{ name: "report", value: "Dark mode flicker\nTheme flips on resume." }],
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
          [{ name: "report", value: "Crash on save\nSteps" }],
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
        command("bug", [{ name: "report", value: "Crash on save\nSteps" }]),
      ),
      env(publicKeyHex, { GITHUB_TOKEN: "" }),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();
    expect(fetch.mock.calls.some(([url]) => String(url).includes("api.github.com"))).toBe(false);
    expect(requestBody(fetch.mock.calls[0]?.[1])).toContain("GitHub isn’t configured");
  });

  it("defers /feature, files an enhancement issue, and replies with the URL", async () => {
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
          "feature",
          [
            {
              name: "report",
              value: "Widget remaining calories\n\nShow leftover calories on the home-screen widget.",
            },
            { name: "platform", value: "both" },
          ],
          { channel_id: IOS_BUG_CHANNEL_ID },
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
        "User-Agent": "fud-ai-discord-feature",
      }),
    });
    const created = JSON.parse(requestBody(githubCall?.[1])) as {
      title: string;
      body: string;
      labels: string[];
    };
    expect(created.title).toBe("Widget remaining calories");
    expect(created.labels).toEqual(["enhancement"]);
    expect(created.body).toContain("Widget remaining calories");
    expect(created.body).toContain("Show leftover calories on the home-screen widget.");
    expect(created.body).toContain("**Platform:** both");
    expect(created.body).toContain("Opened via Discord `/feature`");
    expect(created.body).toContain("Reporter");
    expect(created.body).toContain("`user-1`");
    expect(created.body).toContain(IOS_BUG_CHANNEL_ID);
    expect(created.body).toContain(GUILD_ID);
    expect(created.body).not.toContain("## Summary");
    expect(created.body).not.toContain("ios");

    const discordCall = fetch.mock.calls.find(([url]) => String(url).includes("discord.com/api/v10/webhooks"));
    expect(requestBody(discordCall?.[1])).toBe(JSON.stringify({ content: `Opened ${ISSUE_URL}` }));
    expect(fetch.mock.calls.some(([url]) => String(url).includes("generativelanguage"))).toBe(false);
  });

  it("leaves /feature platform unspecified when omitted, even in a platform channel", async () => {
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
          "feature",
          [{ name: "report", value: "Dark mode for widgets\nMatch system appearance." }],
          { channel_id: ANDROID_BUG_CHANNEL_ID },
        ),
      ),
      env(publicKeyHex),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();
    const created = JSON.parse(requestBody(fetch.mock.calls[0]?.[1])) as {
      labels: string[];
      body: string;
    };
    expect(created.labels).toEqual(["enhancement"]);
    expect(created.body).toContain("Dark mode for widgets");
    expect(created.body).toContain("Match system appearance.");
    expect(created.body).toContain(ANDROID_BUG_CHANNEL_ID);
    expect(created.body).not.toContain("**Platform:**");
    expect(created.body).not.toMatch(/## Platform\n\nAndroid/);
  });

  it("rejects /feature without report", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const response = await handleDiscordInteractionsRequest(
      await signedRequest(privateKey, command("feature", [{ name: "platform", value: "iOS" }])),
      env(publicKeyHex),
    );
    expect(await response.json()).toMatchObject({
      type: 4,
      data: { flags: 64 },
    });
  });

  it("rejects /bug without report", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const response = await handleDiscordInteractionsRequest(
      await signedRequest(privateKey, command("bug", [{ name: "platform", value: "iOS" }])),
      env(publicKeyHex),
    );
    expect(await response.json()).toMatchObject({
      type: 4,
      data: { flags: 64 },
    });
  });

  it("files a /bug issue with a derived title from a long one-line report", async () => {
    const { privateKey, publicKeyHex } = await ed25519Pair();
    const fetch = vi.fn<MockFetch>(async (url) => {
      if (String(url).includes("api.github.com")) {
        return Response.json({ html_url: ISSUE_URL, number: 42 }, { status: 201 });
      }
      return new Response(null, { status: 200 });
    });
    vi.stubGlobal("fetch", fetch);
    const { ctx, flush } = waiters();
    const report =
      "The save button crashes every time I log a meal after coming back from the fasting timer on iPhone 15";

    const response = await handleDiscordInteractionsRequest(
      await signedRequest(privateKey, command("bug", [{ name: "report", value: report }])),
      env(publicKeyHex),
      ctx,
    );
    expect(await response.json()).toEqual({ type: 5 });
    await flush();

    const created = JSON.parse(requestBody(fetch.mock.calls[0]?.[1])) as {
      title: string;
      body: string;
      labels: string[];
    };
    expect(created.title).toBe(deriveIssueTitle(report));
    expect(created.title.endsWith("…")).toBe(true);
    expect(created.labels).toEqual(["bug"]);
    expect(created.body.startsWith(report)).toBe(true);
    expect(created.body).toContain("Reported via Discord `/bug`");
  });

  it("routes the live interactions path on the Worker", async () => {
    const response = await worker.fetch(
      new Request(`https://fud-ai.app${DISCORD_INTERACTIONS_PATH}`, { method: "GET" }),
      { DISCORD_PUBLIC_KEY: "abc", GITHUB_TOKEN: "token" } as unknown as Env,
    );
    expect(response.status).toBe(405);
  });
});
