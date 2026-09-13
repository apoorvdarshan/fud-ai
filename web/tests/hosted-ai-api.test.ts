import { afterEach, describe, expect, it, vi } from "vitest";
import { handleHostedAIRequest, summarizeUpstreamErrorBody, type HostedAIDependencies } from "../hosted-ai-api";
import {
  D1LedgerStore,
  ENTITLEMENT_TTL_MS,
  LEDGER_RETENTION_MS,
  REFUND_MARKER_RETENTION_MS,
  cleanupHostedAILedger,
  loadVerifiedLedger,
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
  /** Number of `refund` calls that should throw before one succeeds. */
  refundFailures = 0;
  /**
   * Number of `refund` calls that should apply the write and *then* throw,
   * simulating D1 committing the transaction but reporting an error.
   */
  refundCommitThenFail = 0;
  refundAttempts = 0;
  /** Receipt ids that have already been refunded (mirrors `hosted_ai_refunds`). */
  refunded = new Set<string>();

  async get(userIdHash: string): Promise<LedgerRow | null> {
    const row = this.rows.get(userIdHash);
    return row ? { ...row } : null;
  }

  async upsertEntitlement(userIdHash: string, snapshot: EntitlementSnapshot, verifiedAt: string): Promise<LedgerRow> {
    const existing = this.rows.get(userIdHash);
    const newer = existing === undefined || verifiedAt >= existing.entitlement_verified_at;
    const row: LedgerRow = existing
      ? {
          ...existing,
          plan: newer ? snapshot.plan : existing.plan,
          credits_granted: Math.max(existing.credits_granted, snapshot.creditsGranted),
          entitlement_verified_at: newer ? verifiedAt : existing.entitlement_verified_at,
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
    this.refundAttempts += 1;
    if (this.refundFailures > 0) {
      this.refundFailures -= 1;
      throw new Error("D1_ERROR: transient");
    }
    const row = this.rows.get(userIdHash);
    if (row && !this.refunded.has(receipt.id)) {
      if (row.usage_day === receipt.day) row.daily_used = Math.max(0, row.daily_used - receipt.fromDaily);
      row.credits_spent = Math.max(0, row.credits_spent - receipt.fromCredits);
      row.version += 1;
    }
    this.refunded.add(receipt.id);
    if (this.refundCommitThenFail > 0) {
      this.refundCommitThenFail -= 1;
      throw new Error("D1_ERROR: network error after commit");
    }
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
  /** Promises handed to `waitUntil` (refund retries). */
  background: Promise<unknown>[];
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
    background: [],
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
  harness.deps = {
    fetch: fetchImpl,
    store,
    now: () => harness.clock.now,
    sleep: async () => {},
    waitUntil: (promise) => {
      harness.background.push(promise);
    },
  };
  return harness;
}

function captureLogs(): { lines: string[]; restore: () => void } {
  const lines: string[] = [];
  const spy = vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    lines.push(args.map(String).join(" "));
  });
  return { lines, restore: () => spy.mockRestore() };
}

afterEach(() => {
  vi.restoreAllMocks();
});

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

  it.each([
    ["a guessable custom id", "alice@example.com"],
    ["an upper-case anonymous id", "$RCAnonymousID:0123456789ABCDEF0123456789ABCDEF"],
    ["a short anonymous id", "$RCAnonymousID:0123456789abcdef"],
    ["a numeric id", "12345678"],
  ])("rejects %s — only RevenueCat anonymous ids are unguessable credentials", async (_label, id) => {
    const harness = createHarness();
    const response = await call(harness, post("/generate", { prompt: "hi" }, { "X-Fud-User-Id": id }));
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "invalid_user_id" });
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

  it("does not charge the ledger for invalid requests or unconfigured providers", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "warm" }));
    harness.store.spendAttempts = 0;

    await call(harness, post("/generate", {}));
    await call(harness, post("/transcribe", { audio: "%%%not-base64%%%" }));
    await call(harness, post("/gemini", geminiBody({ tools: [{ googleSearch: {} }] })));
    harness.env = { ...harness.env, GEMINI_API_KEY: "" };
    await call(harness, post("/generate", { prompt: "hi" }));

    expect(harness.store.spendAttempts).toBe(0);
    expect(harness.store.refundAttempts).toBe(0);
    expect(harness.store.only().daily_used).toBe(1);
  });

  it("retries a failed refund in the background instead of losing the action", async () => {
    const harness = createHarness({ gemini: { status: 500, body: {} } });
    harness.store.refundFailures = 2;
    const logs = captureLogs();
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    expect(response.status).toBe(503);
    expect(harness.background).toHaveLength(1);
    await Promise.all(harness.background);
    logs.restore();

    expect(harness.store.refundAttempts).toBe(3);
    expect(harness.store.only().daily_used).toBe(0);
    expect(logs.lines.some((line) => line.includes("hosted_ai_refund_recovered"))).toBe(true);
  });

  it("logs the full receipt for reconciliation when every refund attempt fails", async () => {
    const harness = createHarness({ gemini: { status: 500, body: {} } });
    harness.store.refundFailures = 99;
    const logs = captureLogs();
    await call(harness, post("/generate", { prompt: "hi" }));
    await Promise.all(harness.background);
    logs.restore();

    expect(harness.store.refundAttempts).toBe(4);
    const failure = logs.lines.map((line) => JSON.parse(line) as Record<string, unknown>).find(
      (entry) => entry.event === "hosted_ai_refund_failed"
    );
    expect(failure).toMatchObject({
      attempts: 4,
      userIdHash: harness.store.only().user_id_hash,
      receipt: { id: expect.stringMatching(/^[0-9a-f-]{36}$/), fromDaily: 1, fromCredits: 0, day: "2026-09-13" },
    });
  });

  it("does not double-credit when a refund committed but D1 reported an error", async () => {
    const harness = createHarness();
    await call(harness, post("/generate", { prompt: "one" }));
    await call(harness, post("/generate", { prompt: "two" }));
    expect(harness.store.only().daily_used).toBe(2);

    harness.gemini = { status: 500, body: {} };
    harness.store.refundCommitThenFail = 1;
    const logs = captureLogs();
    const response = await call(harness, post("/generate", { prompt: "three" }));
    expect(response.status).toBe(503);
    expect(harness.background).toHaveLength(1);
    await Promise.all(harness.background);
    logs.restore();

    expect(harness.store.refundAttempts).toBe(2);
    expect(harness.store.only().daily_used).toBe(2);
    expect(harness.store.refunded.size).toBe(1);
  });

  it("issues a distinct receipt id per spend so refunds cannot collide", async () => {
    const harness = createHarness({ gemini: { status: 500, body: {} } });
    await call(harness, post("/generate", { prompt: "a" }));
    await call(harness, post("/generate", { prompt: "b" }));
    await Promise.all(harness.background);
    expect(harness.store.refundAttempts).toBe(2);
    expect(harness.store.refunded.size).toBe(2);
    expect(harness.store.only().daily_used).toBe(0);
  });

  it("never lets an older RevenueCat verification overwrite a newer plan", async () => {
    const store = new MemoryLedgerStore();
    const hash = "h".repeat(64);
    const pending: Array<{ resolve: (snapshot: EntitlementSnapshot) => void }> = [];
    let now = new Date("2026-09-13T10:00:00.000Z");
    const context = {
      store,
      now: () => now,
      verify: () => new Promise<EntitlementSnapshot>((resolve) => pending.push({ resolve })),
    };

    const tick = () => new Promise<void>((resolve) => setTimeout(resolve, 0));
    const older = loadVerifiedLedger(context, USER_ID, hash);
    await tick();
    now = new Date(now.getTime() + 1_000);
    const newer = loadVerifiedLedger(context, USER_ID, hash);
    await tick();
    expect(pending).toHaveLength(2);

    pending[1]!.resolve({ plan: "pro", creditsGranted: 50 });
    await expect(newer).resolves.toMatchObject({ plan: "pro", credits_granted: 50 });
    pending[0]!.resolve({ plan: "plus", creditsGranted: 0 });
    const losing = await older;

    expect(losing.plan).toBe("pro");
    expect(losing.credits_granted).toBe(50);
    expect(losing.entitlement_verified_at).toBe("2026-09-13T10:00:01.000Z");
    expect(store.only()).toMatchObject({ plan: "pro", entitlement_verified_at: "2026-09-13T10:00:01.000Z" });
  });

  it("retention cleanup keeps rows that have spent purchased credits", async () => {
    const statements: Array<{ sql: string; binds: unknown[] }> = [];
    const database = {
      prepare: (sql: string) => ({
        bind: (...binds: unknown[]) => ({
          run: async () => {
            statements.push({ sql, binds });
            return { success: true, meta: {} };
          },
        }),
      }),
    } as unknown as D1Database;

    const now = new Date("2026-09-13T01:00:00.000Z");
    await cleanupHostedAILedger(database, now);
    expect(statements).toHaveLength(2);
    expect(statements[0]!.sql).toMatch(/DELETE FROM hosted_ai_ledger/);
    expect(statements[0]!.sql).toMatch(/credits_spent = 0/);
    expect(statements[0]!.binds).toEqual([new Date(now.getTime() - LEDGER_RETENTION_MS).toISOString()]);
    expect(statements[1]!.sql).toMatch(/DELETE FROM hosted_ai_refunds/);
    expect(statements[1]!.binds).toEqual([new Date(now.getTime() - REFUND_MARKER_RETENTION_MS).toISOString()]);
  });

  it("D1 refund is a single transaction whose ledger update is gated on the receipt marker", async () => {
    const batches: Array<Array<{ sql: string; binds: unknown[] }>> = [];
    const database = {
      prepare: (sql: string) => ({ bind: (...binds: unknown[]) => ({ sql, binds }) }),
      batch: async (statements: Array<{ sql: string; binds: unknown[] }>) => {
        batches.push(statements);
        return statements.map(() => ({ success: true, meta: {} }));
      },
    } as unknown as D1Database;

    const store = new D1LedgerStore(database);
    const hash = "a".repeat(64);
    const receipt: SpendReceipt = { id: "11111111-2222-4333-8444-555555555555", fromDaily: 1, fromCredits: 0, day: "2026-09-13" };
    await store.refund(hash, receipt, "2026-09-13T01:00:00.000Z");

    expect(batches).toHaveLength(1);
    const [update, marker] = batches[0]!;
    expect(update!.sql).toMatch(/UPDATE hosted_ai_ledger/);
    expect(update!.sql).toMatch(/NOT EXISTS \(SELECT 1 FROM hosted_ai_refunds WHERE receipt_id = \?6\)/);
    expect(update!.binds).toEqual(["2026-09-13", 1, 0, "2026-09-13T01:00:00.000Z", hash, receipt.id]);
    expect(marker!.sql).toMatch(/INSERT OR IGNORE INTO hosted_ai_refunds/);
    expect(marker!.binds).toEqual([receipt.id, hash, "2026-09-13T01:00:00.000Z"]);
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
              parts: [
                { text: "Let me check.", thought: true, thoughtSignature: "sig-1" },
                { functionCall: { name: "get_workouts", args: { days: 7 } }, thoughtSignature: "sig-2" },
              ],
            },
            { role: "user", parts: [{ functionResponse: { name: "get_workouts", response: { sessions: [] } } }] },
          ],
          tools: [{ functionDeclarations: [{ name: "get_workouts" }] }],
          toolConfig: { functionCallingConfig: { mode: "AUTO" } },
        })
      )
    );
    expect(response.status).toBe(200);
    const upstream = harness.calls.find((c) => c.url.includes("generativelanguage"));
    const forwarded = JSON.parse(String(upstream?.init?.body)) as { contents: Array<{ parts: unknown[] }> };
    expect(forwarded.contents[1]!.parts).toEqual([
      { text: "Let me check.", thought: true, thoughtSignature: "sig-1" },
      { functionCall: { name: "get_workouts", args: { days: 7 } }, thoughtSignature: "sig-2" },
    ]);
  });

  it("rejects non-boolean thought metadata and metadata-only parts", async () => {
    const harness = createHarness();
    let response = await call(
      harness,
      post("/gemini", geminiBody({ contents: [{ role: "model", parts: [{ text: "x", thought: "yes" }] }] }))
    );
    await expect(response.json()).resolves.toMatchObject({ detail: "invalid_type:contents[0].parts[0].thought" });
    response = await call(
      harness,
      post("/gemini", geminiBody({ contents: [{ role: "model", parts: [{ thought: true, thoughtSignature: "s" }] }] }))
    );
    await expect(response.json()).resolves.toMatchObject({
      detail: "part_must_have_one_payload:contents[0].parts[0]",
    });
  });

  it.each([
    ["null", null],
    ["an array", [1, 2]],
    ["a string", "prompt"],
    ["a number", 42],
  ])("returns 400 invalid_body when the JSON body is %s", async (_label, body) => {
    const harness = createHarness();
    for (const path of ["/generate", "/transcribe", "/gemini"]) {
      const response = await call(harness, post(path, body));
      expect(response.status).toBe(400);
      await expect(response.json()).resolves.toEqual({ error: "invalid_body" });
    }
    expect(harness.calls.filter((c) => !c.url.includes("revenuecat"))).toHaveLength(0);
    expect(harness.store.only().daily_used).toBe(0);
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

  it("logs only structured provider fields — never the raw body, tokens, or user content", async () => {
    const harness = createHarness({
      deepgram: {
        status: 401,
        body: { err_code: "INVALID_AUTH", err_msg: "Invalid credentials: token dg-key", request_id: "req-1" },
      },
    });
    const logs = captureLogs();
    await call(harness, post("/transcribe", { audio: btoa("audio"), mimeType: "audio/wav" }));

    harness.gemini = {
      status: 400,
      body: { error: { code: 400, status: "INVALID_ARGUMENT", message: `API key gemini-key not valid for "my secret prompt"` } },
    };
    await call(harness, post("/generate", { prompt: "my secret prompt" }));
    logs.restore();

    const joined = logs.lines.join("\n");
    expect(joined).toContain("hosted_ai_upstream_error");
    expect(joined).toContain("err_code=INVALID_AUTH");
    expect(joined).toContain("status=INVALID_ARGUMENT");
    for (const leak of ["dg-key", "gemini-key", "Invalid credentials", "my secret prompt", "not valid"]) {
      expect(joined).not.toContain(leak);
    }
  });

  it("summarizes upstream error bodies without free text", () => {
    expect(summarizeUpstreamErrorBody('{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"quota"}}')).toBe(
      "status=RESOURCE_EXHAUSTED code=429"
    );
    expect(summarizeUpstreamErrorBody('{"err_code":"Bad Request<script>","err_msg":"token abc"}')).toBe(
      "err_code=BadRequestscript"
    );
    expect(summarizeUpstreamErrorBody("<html>gateway timeout</html>")).toBe("non_json_body bytes=28");
    expect(summarizeUpstreamErrorBody('{"message":"free text only"}')).toBe("unrecognized_body bytes=28");
  });

  it("redacts configured provider secrets from generic error logs", async () => {
    const harness = createHarness();
    harness.deps.store = {
      ...harness.store,
      get: async () => {
        throw new Error("D1 rejected query with key gemini-key in it");
      },
    } as unknown as LedgerStore;
    const logs = captureLogs();
    const response = await call(harness, post("/generate", { prompt: "hi" }));
    logs.restore();
    expect(response.status).toBe(500);
    const joined = logs.lines.join("\n");
    expect(joined).toContain("[redacted]");
    expect(joined).not.toContain("gemini-key");
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
