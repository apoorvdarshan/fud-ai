/**
 * Shared test doubles for the hosted AI Worker: an in-memory ledger, a fetch
 * mock for RevenueCat / Gemini / Deepgram / Gemini Live, fake WebSockets and a
 * fake clock with timers for the voice relay.
 */

import { vi } from "vitest";
import { GEMINI_LIVE_URL, handleHostedAIRequest, type HostedAIDependencies } from "../hosted-ai-api";
import type { EntitlementSnapshot, LedgerRow, LedgerStore, SpendReceipt } from "../hosted-ai-ledger";

export const USER_ID = "$RCAnonymousID:0123456789abcdef0123456789abcdef";
export const BASE = "https://fud-ai.app/api/hosted-ai/v1";

export class MemoryLedgerStore implements LedgerStore {
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
  voiceSpendAttempts = 0;
  /** When set, the next `trySpendVoice` call loses the compare-and-swap once. */
  voiceConflictOnce = false;
  /** Number of `trySpendVoice` calls that should throw (D1 unavailable). */
  voiceFailures = 0;
  voiceReceipts = new Map<string, { userIdHash: string; source: "allowance" | "credits" }>();

  async getVoiceSpend(userIdHash: string, receiptId: string): Promise<"allowance" | "credits" | null> {
    const receipt = this.voiceReceipts.get(receiptId);
    return receipt?.userIdHash === userIdHash ? receipt.source : null;
  }

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
          trial_expires_at: newer ? snapshot.trialExpiresAt ?? null : existing.trial_expires_at ?? null,
          credits_granted: Math.max(existing.credits_granted, snapshot.creditsGranted),
          entitlement_verified_at: newer ? verifiedAt : existing.entitlement_verified_at,
          version: existing.version + 1,
        }
      : {
          user_id_hash: userIdHash,
          plan: snapshot.plan,
          trial_expires_at: snapshot.trialExpiresAt ?? null,
          credits_granted: snapshot.creditsGranted,
          credits_spent: 0,
          usage_day: verifiedAt.slice(0, 10),
          daily_used: 0,
          entitlement_verified_at: verifiedAt,
          version: 0,
          voice_month: "",
          voice_seconds_used: 0,
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

  async trySpendVoice(
    userIdHash: string,
    expectedVersion: number,
    next: { voiceMonth: string; voiceSecondsUsed: number; creditsSpent: number },
    _now: string,
    receipt: { id: string; source: "allowance" | "credits" }
  ): Promise<boolean> {
    this.voiceSpendAttempts += 1;
    if (this.voiceFailures > 0) {
      this.voiceFailures -= 1;
      throw new Error("D1_ERROR: transient");
    }
    const row = this.rows.get(userIdHash);
    if (!row || row.version !== expectedVersion || this.voiceReceipts.has(receipt.id)) return false;
    if (this.voiceConflictOnce) {
      this.voiceConflictOnce = false;
      row.version += 1;
      return false;
    }
    row.voice_month = next.voiceMonth;
    row.voice_seconds_used = next.voiceSecondsUsed;
    row.credits_spent = next.creditsSpent;
    row.version += 1;
    this.voiceReceipts.set(receipt.id, { userIdHash, source: receipt.source });
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

type Listener = (event: { data?: unknown; code?: number; reason?: string; wasClean?: boolean }) => void;

/**
 * Stand-in for one Workers `WebSocket` end. `receive` delivers a frame from the
 * peer; `sent` and `closeCalls` record what the Worker did with this end.
 */
export class FakeSocket {
  binaryType: "blob" | "arraybuffer" = "blob";
  readyState = 0;
  accepted = false;
  sent: Array<string | ArrayBuffer> = [];
  closeCalls: Array<{ code: number | undefined; reason: string | undefined }> = [];
  private readonly listeners = new Map<string, Listener[]>();

  accept(): void {
    this.accepted = true;
    this.readyState = 1;
  }

  addEventListener(type: string, listener: Listener): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  send(data: string | ArrayBuffer): void {
    if (this.readyState !== 1) throw new TypeError("WebSocket is not open");
    this.sent.push(data);
  }

  close(code?: number, reason?: string): void {
    if (this.readyState === 3) return;
    this.closeCalls.push({ code, reason });
    this.readyState = 3;
  }

  receive(data: string | ArrayBuffer): void {
    for (const listener of this.listeners.get("message") ?? []) listener({ data });
  }

  receiveJson(message: unknown): void {
    this.receive(JSON.stringify(message));
  }

  /** The peer closed the connection (the Workers runtime has already replied). */
  remoteClose(code: number, reason = ""): void {
    this.readyState = 3;
    for (const listener of this.listeners.get("close") ?? []) listener({ code, reason, wasClean: code !== 1006 });
  }

  get closedWith(): number | undefined {
    return this.closeCalls[0]?.code;
  }

  /** Every frame sent, decoded as JSON (text and binary alike). */
  sentJson(): Array<Record<string, unknown>> {
    return this.sent.map(
      (frame) => JSON.parse(typeof frame === "string" ? frame : new TextDecoder().decode(frame)) as Record<string, unknown>
    );
  }

  /** The Worker's own `{"fudVoice": …}` messages. */
  fudVoice(): Array<Record<string, unknown>> {
    return this.sentJson()
      .filter((message) => message.fudVoice !== undefined)
      .map((message) => message.fudVoice as Record<string, unknown>);
  }
}

/** Lets promise chains (in-memory ledger writes) run to completion. */
export async function settle(): Promise<void> {
  for (let turn = 0; turn < 5; turn += 1) {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }
}

/** Timers driven by the harness clock, so a 15-minute session runs instantly. */
export class FakeTimers {
  private timers: Array<{ at: number; order: number; callback: () => void; cancelled: boolean }> = [];
  private order = 0;

  constructor(private readonly clock: { now: Date }) {}

  readonly setTimer = (callback: () => void, ms: number): (() => void) => {
    const timer = { at: this.clock.now.getTime() + Math.max(0, ms), order: this.order++, callback, cancelled: false };
    this.timers.push(timer);
    return () => {
      timer.cancelled = true;
    };
  };

  get pending(): number {
    return this.timers.filter((timer) => !timer.cancelled).length;
  }

  /** Moves the clock forward, firing due timers in order and letting their async work settle. */
  async advance(ms: number): Promise<void> {
    const target = this.clock.now.getTime() + ms;
    for (;;) {
      await settle();
      const due = this.timers
        .filter((timer) => !timer.cancelled && timer.at <= target)
        .sort((left, right) => left.at - right.at || left.order - right.order)[0];
      if (!due) break;
      due.cancelled = true;
      this.clock.now = new Date(due.at);
      due.callback();
    }
    this.clock.now = new Date(target);
    await settle();
  }
}

export interface Harness {
  env: Parameters<typeof handleHostedAIRequest>[1];
  deps: HostedAIDependencies;
  store: MemoryLedgerStore;
  calls: Array<{ url: string; init: RequestInit | undefined }>;
  revenueCat: { plan: "none" | "plus" | "pro"; credits?: Array<{ product: string; id: string }>; status?: number };
  gemini: { status?: number; body?: unknown; throwName?: string };
  deepgram: { status?: number; body?: unknown };
  /** Gemini Live handshake: succeeds with a fake socket unless a status, error or hang is set. */
  geminiLive: { status?: number; body?: unknown; throwName?: string; hang?: boolean };
  rateLimit: { user: boolean; address: boolean };
  clock: { now: Date };
  timers: FakeTimers;
  /** Promises handed to `waitUntil` (refund retries, voice block charges). */
  background: Promise<unknown>[];
  /** Worker ends of the WebSocketPairs the relay created (the app talks to these). */
  appSockets: FakeSocket[];
  /** Gemini Live sockets the relay opened. */
  upstreamSockets: FakeSocket[];
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

export function createHarness(
  overrides: Partial<Pick<Harness, "revenueCat" | "gemini" | "deepgram" | "geminiLive">> = {}
): Harness {
  const store = new MemoryLedgerStore();
  const clock = { now: new Date("2026-09-13T01:00:00.000Z") };
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
    geminiLive: overrides.geminiLive ?? {},
    rateLimit: { user: true, address: true },
    clock,
    timers: new FakeTimers(clock),
    env: undefined as unknown as Harness["env"],
    deps: {},
    background: [],
    appSockets: [],
    upstreamSockets: [],
  };

  const fetchImpl: typeof fetch = async (input, init) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    harness.calls.push({ url, init });
    if (url.startsWith("https://api.revenuecat.com/")) {
      const status = harness.revenueCat.status ?? 200;
      return new Response(status === 200 ? JSON.stringify(subscriberPayload(harness)) : "{}", { status });
    }
    if (url === GEMINI_LIVE_URL) {
      const live = harness.geminiLive;
      if (live.hang) {
        return await new Promise<Response>((_, reject) => {
          init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        });
      }
      if (live.throwName) {
        const error = new Error("connect failed");
        error.name = live.throwName;
        throw error;
      }
      if (live.status !== undefined) {
        return new Response(JSON.stringify(live.body ?? {}), { status: live.status });
      }
      const socket = new FakeSocket();
      harness.upstreamSockets.push(socket);
      // Node's Response cannot carry status 101 or a socket; the relay only reads these two fields.
      return { status: 101, webSocket: socket } as unknown as Response;
    }
    if (url.startsWith("https://generativelanguage.googleapis.com/")) {
      if (harness.gemini.throwName) {
        const error = new Error("upstream fetch failed");
        error.name = harness.gemini.throwName;
        throw error;
      }
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
    live: {
      webSocketPair: () => {
        const server = new FakeSocket();
        harness.appSockets.push(server);
        return { client: new FakeSocket() as unknown as WebSocket, server: server as unknown as WebSocket };
      },
      upgradeResponse: (client) => ({ status: 101, webSocket: client }) as unknown as Response,
      setTimer: harness.timers.setTimer,
    },
  };
  return harness;
}

export function captureLogs(): { lines: string[]; restore: () => void } {
  const lines: string[] = [];
  const spy = vi.spyOn(console, "error").mockImplementation((...args: unknown[]) => {
    lines.push(args.map(String).join(" "));
  });
  return { lines, restore: () => spy.mockRestore() };
}

export async function call(harness: Harness, request: Request): Promise<Response> {
  return handleHostedAIRequest(request, harness.env, harness.deps);
}
