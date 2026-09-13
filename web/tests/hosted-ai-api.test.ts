import { describe, expect, it } from "vitest";
import { handleHostedAIRequest, type HostedAIDependencies } from "../hosted-ai-api";
import {
  ENTITLEMENT_TTL_MS,
  parseRevenueCatSubscriber,
  spendFromLedger,
  type EntitlementSnapshot,
  type LedgerRow,
  type LedgerStore,
  type SpendReceipt,
} from "../hosted-ai-ledger";

const USER_ID = "$RCAnonymousID:0123456789abcdef0123456789abcdef";
const BASE = "https://fud-ai.app/api/hosted-ai/v1";

class MemoryLedgerStore implements LedgerStore {
  rows = new Map<string, LedgerRow>();
  spendAttempts = 0;
  /** When set, the next `trySpend` call fails once to simulate a concurrent writer. */
  conflictOnce = false;

  async get(userIdHash: string): Promise<LedgerRow | null> {
    const row = this.rows.get(userIdHash);
    return row ? { ...row } : null;
  }

  async upsertEntitlement(userIdHash: string, snapshot: EntitlementSnapshot, verifiedAt: string): Promise<LedgerRow> {
    const existing = this.rows.get(userIdHash);
    const row: LedgerRow = existing
      ? {
          ...existing,
          plan: snapshot.plan,
          credits_granted: Math.max(existing.credits_granted, snapshot.creditsGranted),
          entitlement_verified_at: verifiedAt,
          version: existing.version + 1,
        }
      : {
          user_id_hash: userIdHash,
          plan: snapshot.plan,
          credits_granted: snapshot.creditsGranted,
          credits_spent: 0,
          usage_day: verifiedAt.slice(0, 10),
          daily_used: 0,
          entitlement_verified_at: verifiedAt,
          version: 0,
        };
    this.rows.set(userIdHash, row);
    return { ...row };
  }

  async trySpend(
    userIdHash: string,
    expectedVersion: number,
    next: { usageDay: string; dailyUsed: number; creditsSpent: number }
  ): Promise<boolean> {
    this.spendAttempts += 1;
    const row = this.rows.get(userIdHash);
    if (!row || row.version !== expectedVersion) return false;
    if (this.conflictOnce) {
      this.conflictOnce = false;
      row.version += 1;
      return false;
    }
    row.usage_day = next.usageDay;
    row.daily_used = next.dailyUsed;
    row.credits_spent = next.creditsSpent;
    row.version += 1;
    return true;
  }

  async refund(userIdHash: string, receipt: SpendReceipt): Promise<void> {
    const row = this.rows.get(userIdHash);
    if (!row) return;
    if (row.usage_day === receipt.day) row.daily_used = Math.max(0, row.daily_used - receipt.fromDaily);
    row.credits_spent = Math.max(0, row.credits_spent - receipt.fromCredits);
    row.version += 1;
  }

  only(): LedgerRow {
    const [row] = this.rows.values();
    if (!row) throw new Error("no ledger row");
    return row;
  }
}

interface Harness {
  env: Parameters<typeof handleHostedAIRequest>[1];
  deps: HostedAIDependencies;
  store: MemoryLedgerStore;
  calls: Array<{ url: string; init: RequestInit | undefined }>;
  revenueCat: { plan: "none" | "plus" | "pro"; credits?: Array<{ product: string; id: string }>; status?: number };
  gemini: { status?: number; body?: unknown };
  deepgram: { status?: number; body?: unknown };
  rateLimit: { user: boolean; address: boolean };
  clock: { now: Date };
}

function subscriberPayload(harness: Harness): unknown {
  const expires = new Date(harness.clock.now.getTime() + 86_400_000).toISOString();
  const entitlements: Record<string, unknown> = {};
  if (harness.revenueCat.plan === "plus") entitlements.plus = { expires_date: expires };
  if (harness.revenueCat.plan === "pro") {
    entitlements.pro = { expires_date: expires };
    entitlements.plus = { expires_date: expires };
  }
  const nonSubscriptions: Record<string, Array<{ id: string }>> = {};
  for (const credit of harness.revenueCat.credits ?? []) {
    (nonSubscriptions[credit.product] ??= []).push({ id: credit.id });
  }
  return { subscriber: { entitlements, non_subscriptions: nonSubscriptions } };
}

function createHarness(overrides: Partial<Pick<Harness, "revenueCat" | "gemini" | "deepgram">> = {}): Harness {
  const store = new MemoryLedgerStore();
  const harness: Harness = {
    store,
    calls: [],
    revenueCat: overrides.revenueCat ?? { plan: "plus" },
    gemini: overrides.gemini ?? {
      body: { candidates: [{ content: { parts: [{ text: "ok" }] } }] },
    },
    deepgram: overrides.deepgram ?? {
      body: { results: { channels: [{ alternatives: [{ transcript: "two eggs" }] }] } },
    },
    rateLimit: { user: true, address: true },
    clock: { now: new Date("2026-09-13T01:00:00.000Z") },
    env: undefined as unknown as Harness["env"],
    deps: {},
  };

  const fetchImpl: typeof fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    harness.calls.push({ url, init });
    if (url.startsWith("https://api.revenuecat.com/")) {
      const status = harness.revenueCat.status ?? 200;
      return new Response(status === 200 ? JSON.stringify(subscriberPayload(harness)) : "{}", { status });
    }
    if (url.startsWith("https://generativelanguage.googleapis.com/")) {
      return new Response(JSON.stringify(harness.gemini.body ?? {}), { status: harness.gemini.status ?? 200 });
    }
    if (url.startsWith("https://api.deepgram.com/")) {
      return new Response(JSON.stringify(harness.deepgram.body ?? {}), { status: harness.deepgram.status ?? 200 });
    }
    throw new Error(`unexpected fetch ${url}`);
  };

  harness.env = {
    GEMINI_API_KEY: "gemini-key",
    DEEPGRAM_API_KEY: "dg-key",
    REVENUECAT_API_KEY: "sk_test",
    CHALLENGE_DB: {
      withSession: () => {
        throw new Error("D1 must not be used when a store is injected");
      },
    } as unknown as D1Database,
    HOSTED_AI_USER_RATE_LIMITER: { limit: async () => ({ success: harness.rateLimit.user }) },
    HOSTED_AI_ADDRESS_RATE_LIMITER: { limit: async () => ({ success: harness.rateLimit.address }) },
  };
  harness.deps = { fetch: fetchImpl, store, now: () => harness.clock.now };
  return harness;
}

function post(path: string, body: unknown, headers: Record<string, string> = {}): Request {
  return new Request(`${BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Fud-User-Id": USER_ID, ...headers },
    body: JSON.stringify(body),
  });
}

function geminiBody(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    requestBody: {
      contents: [{ role: "user", parts: [{ text: "hello" }] }],
      ...extra,
    },
  };
}

async function call(harness: Harness, request: Request): Promise<Response> {
  return handleHostedAIRequest(request, harness.env, harness.deps);
}

describe("hosted-ai identity and auth", () => {
  it("rejects requests without a RevenueCat user id", async () => {
    const harness = createHarness();
    const response = await call(
      harness,
      new Request(`${BASE}/generate`, { method: "POST", body: JSON.stringify({ prompt: "hi" }) })
    );
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "invalid_user_id" });
    expect(harness.calls).toHaveLength(0);
  });

  it("rejects malformed user ids before touching RevenueCat", async () => {
    const harness = createHarness();
    const response = await call(
      harness,
      post("/generate", { prompt: "hi" }, { "X-Fud-User-Id": "../etc/passwd; drop" })
    );
    expect(response.status).toBe(401);
    expect(harness.calls).toHaveLength(0);
  });

  it("ignores the legacy shared-secret and client-asserted plan headers", async () => {
    const harness = createHarness({ revenueCat: { plan: "none" } });
    const response = await call(
      harness,
      post("/generate", { prompt: "hi" }, { Authorization: "Bearer anything", "X-Fud-Plan": "pro" })
    );
    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toMatchObject({ error: "subscription_required" });
    expect(harness.calls.some((c) => c.url.includes("generativelanguage"))).toBe(false);
  });

  it("verifies entitlements with RevenueCat using the server-side secret", async () => {
    const harness = createHarness();
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(200);
    const rcCall = harness.calls.find((c) => c.url.startsWith("https://api.revenuecat.com/"));
    expect(rcCall?.url).toBe(`https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(USER_ID)}`);
    expect(new Headers(rcCall?.init?.headers).get("Authorization")).toBe("Bearer sk_test");
    expect(response.headers.get("X-Fud-Quota-Plan")).toBe("plus");
    expect(response.headers.get("X-Fud-Quota-Daily-Used")).toBe("1");
    expect(response.headers.get("X-Fud-Quota-Daily-Limit")).toBe("30");
  });

  it("caches the verified entitlement and re-verifies after the TTL", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "one" }));
    await call(harness, post("/generate", { prompt: "two" }));
    const rcCalls = () => harness.calls.filter((c) => c.url.startsWith("https://api.revenuecat.com/")).length;
    expect(rcCalls()).toBe(1);

    harness.clock.now = new Date(harness.clock.now.getTime() + ENTITLEMENT_TTL_MS.plus + 1);
    await call(harness, post("/generate", { prompt: "three" }));
    expect(rcCalls()).toBe(2);
  });

  it("returns 503 when RevenueCat is unavailable and nothing is cached", async () => {
    const harness = createHarness({ revenueCat: { plan: "plus", status: 500 } });
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({ error: "entitlement_unavailable" });
  });

  it("serves a stale cached entitlement while RevenueCat is down", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "warm" }));
    harness.revenueCat.status = 503;
    harness.clock.now = new Date(harness.clock.now.getTime() + ENTITLEMENT_TTL_MS.plus + 1);
    const response = await call(harness, post("/generate", { prompt: "stale" }));
    expect(response.status).toBe(200);
  });

  it("returns 503 when the RevenueCat key is not configured", async () => {
    const harness = createHarness();
    harness.env = { ...harness.env, REVENUECAT_API_KEY: "" };
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({ error: "entitlements_not_configured" });
  });

  it("enforces the per-user and per-address rate limiters", async () => {
    const harness = createHarness();
    harness.rateLimit.user = false;
    let response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("60");

    harness.rateLimit.user = true;
    harness.rateLimit.address = false;
    response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(429);
    expect(harness.calls).toHaveLength(0);
  });

  it("rejects non-POST on proxy routes and non-GET on quota", async () => {
    const harness = createHarness();
    let response = await call(harness, new Request(`${BASE}/generate`, { method: "GET" }));
    expect(response.status).toBe(405);
    response = await call(harness, post("/quota", {}));
    expect(response.status).toBe(405);
  });
});

describe("hosted-ai server-side ledger", () => {
  it("meters each round-trip and rejects with 402 once daily pool and credits are gone", async () => {
    const harness = createHarness({
      revenueCat: {
        plan: "plus",
        credits: [{ product: "com.apoorvdarshan.calorietracker.credits.50", id: "txn-1" }],
      },
    });
    const first = await call(harness, post("/generate", { prompt: "hi" }));
    expect(first.headers.get("X-Fud-Quota-Credits")).toBe("50");

    const row = harness.store.only();
    row.daily_used = 30;
    row.credits_spent = 50;

    const exhausted = await call(harness, post("/generate", { prompt: "hi" }));
    expect(exhausted.status).toBe(402);
    await expect(exhausted.json()).resolves.toMatchObject({
      error: "quota_exceeded",
      quota: { plan: "plus", dailyUsed: 30, dailyLimit: 30, creditBank: 0 },
    });
    expect(harness.calls.filter((c) => c.url.includes("generativelanguage"))).toHaveLength(1);
  });

  it("spends from the daily pool before the credit bank", async () => {
    const harness = createHarness({
      revenueCat: {
        plan: "plus",
        credits: [{ product: "com.apoorvdarshan.calorietracker.credits.150", id: "txn-a" }],
      },
    });
    await call(harness, post("/generate", { prompt: "warm" }));
    const row = harness.store.only();
    row.daily_used = 30;

    const response = await call(harness, post("/generate", { prompt: "credit" }));
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Fud-Quota-Daily-Used")).toBe("30");
    expect(response.headers.get("X-Fud-Quota-Credits")).toBe("149");
  });

  it("resets the daily pool on the UTC day boundary", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "warm" }));
    harness.store.only().daily_used = 30;

    harness.clock.now = new Date("2026-09-14T00:00:01.000Z");
    const response = await call(harness, post("/generate", { prompt: "new day" }));
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Fud-Quota-Day")).toBe("2026-09-14");
    expect(response.headers.get("X-Fud-Quota-Daily-Used")).toBe("1");
  });

  it("reconciles credits idempotently by transaction id across re-verifications", async () => {
    const harness = createHarness({
      revenueCat: {
        plan: "plus",
        credits: [
          { product: "com.apoorvdarshan.calorietracker.credits.50", id: "txn-1" },
          { product: "com.apoorvdarshan.calorietracker.credits.50", id: "txn-1" },
          { product: "com.apoorvdarshan.calorietracker.credits.400", id: "txn-2" },
          { product: "com.apoorvdarshan.calorietracker.tip.snack", id: "txn-3" },
        ],
      },
    });
    const first = await call(harness, post("/generate", { prompt: "hi" }));
    expect(first.headers.get("X-Fud-Quota-Credits")).toBe("450");

    harness.clock.now = new Date(harness.clock.now.getTime() + ENTITLEMENT_TTL_MS.plus + 1);
    const second = await call(harness, post("/generate", { prompt: "hi" }));
    expect(second.headers.get("X-Fud-Quota-Credits")).toBe("450");
    expect(harness.store.only().credits_granted).toBe(450);
  });

  it("refunds the metered action when the upstream call fails", async () => {
    const harness = createHarness({ gemini: { status: 500, body: { error: { message: "secret detail" } } } });
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    const body = await response.json();
    expect(body).toEqual({ error: "upstream_unavailable" });
    expect(JSON.stringify(body)).not.toContain("secret detail");
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("retries the compare-and-swap when a concurrent writer bumps the version", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "warm" }));
    harness.store.conflictOnce = true;
    harness.store.spendAttempts = 0;
    const response = await call(harness, post("/generate", { prompt: "race" }));
    expect(response.status).toBe(200);
    expect(harness.store.spendAttempts).toBe(2);
    expect(harness.store.only().daily_used).toBe(2);
  });

  it("exposes the quota snapshot on GET /quota and forces a refresh with ?refresh=1", async () => {
    const harness = createHarness();
    const request = (query = "") =>
      new Request(`${BASE}/quota${query}`, { method: "GET", headers: { "X-Fud-User-Id": USER_ID } });

    const response = await call(harness, request());
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      quota: { plan: "plus", day: "2026-09-13", dailyUsed: 0, dailyLimit: 30, creditBank: 0 },
    });

    harness.revenueCat.plan = "pro";
    await call(harness, request());
    expect(harness.calls.filter((c) => c.url.includes("revenuecat"))).toHaveLength(1);
    const refreshed = await call(harness, request("?refresh=1"));
    await expect(refreshed.json()).resolves.toMatchObject({ quota: { plan: "pro", dailyLimit: 60 } });
    expect(harness.calls.filter((c) => c.url.includes("revenuecat"))).toHaveLength(2);
  });

  it("does not spend when the ledger is exhausted (pure spend helper)", async () => {
    const store = new MemoryLedgerStore();
    const row = await store.upsertEntitlement("h".repeat(64), { plan: "pro", creditsGranted: 0 }, "2026-09-13T00:00:00.000Z");
    row.daily_used = 60;
    store.rows.set(row.user_id_hash, row);
    const outcome = await spendFromLedger(
      { store, now: () => new Date("2026-09-13T12:00:00.000Z"), verify: async () => ({ plan: "pro", creditsGranted: 0 }) },
      row.user_id_hash,
      row,
      1
    );
    expect(outcome.status).toBe("quota_exceeded");
  });
});

describe("hosted-ai /gemini body allow-list", () => {
  it("forwards only allow-listed fields", async () => {
    const harness = createHarness();
    const response = await call(
      harness,
      post(
        "/gemini",
        geminiBody({
          systemInstruction: { parts: [{ text: "You are a coach." }] },
          generationConfig: { temperature: 0.4, maxOutputTokens: 1024 },
          tools: [
            {
              functionDeclarations: [
                { name: "get_weights", description: "Recent weights", parameters: { type: "object", properties: {} } },
              ],
            },
          ],
        })
      )
    );
    expect(response.status).toBe(200);
    const upstream = harness.calls.find((c) => c.url.includes("generativelanguage"));
    const forwarded = JSON.parse(String(upstream?.init?.body)) as Record<string, unknown>;
    expect(Object.keys(forwarded).sort()).toEqual(["contents", "generationConfig", "systemInstruction", "tools"]);
    expect(forwarded.tools).toEqual([
      {
        functionDeclarations: [
          { name: "get_weights", description: "Recent weights", parameters: { type: "object", properties: {} } },
        ],
      },
    ]);
  });

  it.each([
    ["googleSearch tool", { tools: [{ googleSearch: {} }] }, "unsupported_field:tools[0].googleSearch"],
    ["codeExecution tool", { tools: [{ codeExecution: {} }] }, "unsupported_field:tools[0].codeExecution"],
    ["safetySettings", { safetySettings: [] }, "unsupported_field:requestBody.safetySettings"],
    ["cachedContent", { cachedContent: "x" }, "unsupported_field:requestBody.cachedContent"],
    ["candidateCount > 1", { generationConfig: { candidateCount: 4 } }, "out_of_range:generationConfig.candidateCount"],
    ["huge maxOutputTokens", { generationConfig: { maxOutputTokens: 65_536 } }, "out_of_range:generationConfig.maxOutputTokens"],
    ["responseSchema", { generationConfig: { responseSchema: {} } }, "unsupported_field:generationConfig.responseSchema"],
    ["fileData part", { contents: [{ parts: [{ fileData: { fileUri: "gs://x" } }] }] }, "unsupported_field:contents[0].parts[0].fileData"],
    ["system role", { contents: [{ role: "system", parts: [{ text: "x" }] }] }, "invalid_role:contents[0].role"],
    ["toolConfig without tools", { toolConfig: { functionCallingConfig: { mode: "ANY" } } }, "requires_tools:toolConfig"],
  ])("rejects %s", async (_label, extra, detail) => {
    const harness = createHarness();
    const response = await call(harness, post("/gemini", geminiBody(extra as Record<string, unknown>)));
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "invalid_request_body", detail });
    expect(harness.calls.some((c) => c.url.includes("generativelanguage"))).toBe(false);
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("caps inline images and mime types", async () => {
    const harness = createHarness();
    const image = { inlineData: { mimeType: "image/jpeg", data: "AAAA" } };
    let response = await call(
      harness,
      post("/gemini", geminiBody({ contents: [{ parts: [image, image, image, image] }] }))
    );
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({ detail: "too_many_images:contents[0].parts[3]" });

    response = await call(
      harness,
      post("/gemini", geminiBody({ contents: [{ parts: [{ inlineData: { mimeType: "application/pdf", data: "AAAA" } }] }] }))
    );
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      detail: "unsupported_mime_type:contents[0].parts[0].inlineData.mimeType",
    });
  });

  it("accepts the coach tool-calling round-trip shape", async () => {
    const harness = createHarness();
    const response = await call(
      harness,
      post(
        "/gemini",
        geminiBody({
          contents: [
            { role: "user", parts: [{ text: "How much did I lift?" }] },
            {
              role: "model",
              parts: [{ functionCall: { name: "get_workouts", args: { days: 7 } }, thoughtSignature: "sig" }],
            },
            { role: "user", parts: [{ functionResponse: { name: "get_workouts", response: { sessions: [] } } }] },
          ],
          tools: [{ functionDeclarations: [{ name: "get_workouts" }] }],
          toolConfig: { functionCallingConfig: { mode: "AUTO" } },
        })
      )
    );
    expect(response.status).toBe(200);
  });

  it("returns 400 for a missing requestBody", async () => {
    const harness = createHarness();
    const response = await call(harness, post("/gemini", {}));
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "missing_request_body" });
  });
});

describe("hosted-ai upstream error hygiene", () => {
  it("never echoes Gemini error bodies", async () => {
    const harness = createHarness({
      gemini: { status: 400, body: { error: { message: "API key not valid. Please pass a valid API key." } } },
    });
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(502);
    const text = await response.text();
    expect(text).toBe(JSON.stringify({ error: "upstream_error" }));
  });

  it("never echoes Deepgram error bodies and refunds the action", async () => {
    const harness = createHarness({ deepgram: { status: 401, body: { err_msg: "Invalid credentials: token dg-key" } } });
    const response = await call(harness, post("/transcribe", { audio: btoa("audio"), mimeType: "audio/wav" }));
    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain("dg-key");
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("maps upstream 429 to 503 with Retry-After", async () => {
    const harness = createHarness({ gemini: { status: 429, body: {} } });
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    expect(response.headers.get("Retry-After")).toBe("30");
  });

  it("transcribes audio and meters it as one action", async () => {
    const harness = createHarness();
    const response = await call(harness, post("/transcribe", { audio: btoa("audio"), mimeType: "audio/m4a", language: "en" }));
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ text: "two eggs" });
    expect(response.headers.get("X-Fud-Quota-Daily-Used")).toBe("1");
    const upstream = harness.calls.find((c) => c.url.includes("deepgram"));
    expect(upstream?.url).toContain("language=en");
  });

  it("rejects unsupported audio mime types and languages", async () => {
    const harness = createHarness();
    let response = await call(harness, post("/transcribe", { audio: btoa("a"), mimeType: "application/octet-stream" }));
    expect(response.status).toBe(400);
    response = await call(harness, post("/transcribe", { audio: btoa("a"), language: "en; DROP" }));
    expect(response.status).toBe(400);
  });

  it("returns 503 when provider keys are not configured (mock-friendly deploys)", async () => {
    const harness = createHarness();
    harness.env = { ...harness.env, GEMINI_API_KEY: "", DEEPGRAM_API_KEY: "" };
    let response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({ error: "gemini_not_configured" });
    response = await call(harness, post("/transcribe", { audio: btoa("a") }));
    expect(response.status).toBe(503);
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("returns 400 on missing prompt and 413 on oversized bodies", async () => {
    const harness = createHarness();
    let response = await call(harness, post("/generate", {}));
    expect(response.status).toBe(400);
    response = await call(
      harness,
      new Request(`${BASE}/generate`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Fud-User-Id": USER_ID, "Content-Length": String(32 * 1024 * 1024) },
        body: JSON.stringify({ prompt: "hi" }),
      })
    );
    expect(response.status).toBe(413);
    expect(harness.store.only().daily_used).toBe(0);
  });
});

describe("parseRevenueCatSubscriber", () => {
  const now = new Date("2026-09-13T00:00:00.000Z");

  it("prefers pro over plus and ignores expired entitlements", () => {
    expect(
      parseRevenueCatSubscriber(
        {
          subscriber: {
            entitlements: {
              pro: { expires_date: "2026-01-01T00:00:00Z" },
              plus: { expires_date: "2027-01-01T00:00:00Z" },
            },
          },
        },
        now
      )
    ).toEqual({ plan: "plus", creditsGranted: 0 });
    expect(
      parseRevenueCatSubscriber({ subscriber: { entitlements: { pro: { expires_date: null } } } }, now)
    ).toEqual({ plan: "pro", creditsGranted: 0 });
  });

  it("returns none for unknown subscribers", () => {
    expect(parseRevenueCatSubscriber({ subscriber: {} }, now)).toEqual({ plan: "none", creditsGranted: 0 });
  });

  it("throws on malformed payloads", () => {
    expect(() => parseRevenueCatSubscriber({}, now)).toThrow("revenuecat_invalid_payload");
  });
});
