/**
 * Server-side entitlement verification and usage ledger for the hosted path.
 *
 * Identity: the RevenueCat app user id sent by the client. The Worker never
 * trusts a client-asserted plan — it verifies the subscriber with the
 * RevenueCat REST API (secret key held only in Worker env), caches the
 * result in D1, and meters every upstream round-trip against a per-user
 * ledger:
 *
 *   daily pool (UTC day, reset by plan limit) → credit bank → 402
 *
 * Credits are reconciled from RevenueCat `non_subscriptions` by transaction
 * id, so grants are idempotent and survive reinstalls/backups. Spending is a
 * compare-and-swap on the ledger row `version`, so concurrent requests from
 * the same user cannot double-spend.
 */

export const HOSTED_AI_CREDIT_PRODUCTS: Readonly<Record<string, number>> = {
  "com.apoorvdarshan.calorietracker.credits.50": 50,
  "com.apoorvdarshan.calorietracker.credits.150": 150,
  "com.apoorvdarshan.calorietracker.credits.400": 400,
};

export const HOSTED_AI_DAILY_LIMITS: Readonly<Record<HostedPlan, number>> = {
  none: 0,
  plus: 30,
  pro: 60,
};

const PLUS_ENTITLEMENT_ID = "plus";
const PRO_ENTITLEMENT_ID = "pro";

/** How long a verified entitlement is trusted before re-checking RevenueCat. */
export const ENTITLEMENT_TTL_MS: Readonly<Record<HostedPlan, number>> = {
  none: 2 * 60 * 1000,
  plus: 15 * 60 * 1000,
  pro: 15 * 60 * 1000,
};

/** If RevenueCat is unreachable, keep serving a previously verified plan for this long. */
export const ENTITLEMENT_STALE_GRACE_MS = 24 * 60 * 60 * 1000;
const REVENUECAT_TIMEOUT_MS = 8_000;
const SPEND_RETRY_ATTEMPTS = 3;

/** Ledger rows untouched for this long are pruned by the scheduled job. */
export const LEDGER_RETENTION_MS = 180 * 24 * 60 * 60 * 1000;

export type HostedPlan = "none" | "plus" | "pro";

export interface LedgerRow {
  user_id_hash: string;
  plan: HostedPlan;
  credits_granted: number;
  credits_spent: number;
  usage_day: string;
  daily_used: number;
  entitlement_verified_at: string;
  version: number;
}

export interface EntitlementSnapshot {
  plan: HostedPlan;
  creditsGranted: number;
}

export interface QuotaSnapshot {
  plan: HostedPlan;
  day: string;
  dailyUsed: number;
  dailyLimit: number;
  creditBank: number;
}

export interface SpendReceipt {
  fromDaily: number;
  fromCredits: number;
  day: string;
}

export type SpendOutcome =
  | { status: "spent"; receipt: SpendReceipt; quota: QuotaSnapshot }
  | { status: "quota_exceeded"; quota: QuotaSnapshot };

/** Storage abstraction so tests can run against an in-memory ledger. */
export interface LedgerStore {
  get(userIdHash: string): Promise<LedgerRow | null>;
  /**
   * Records a RevenueCat verification. Must be monotonic in `verifiedAt`: when
   * the stored row was verified more recently (overlapping refreshes finishing
   * out of order), the stored plan/timestamp win and the current row is
   * returned. `credits_granted` only ever grows regardless.
   */
  upsertEntitlement(
    userIdHash: string,
    snapshot: EntitlementSnapshot,
    verifiedAt: string
  ): Promise<LedgerRow>;
  /** Compare-and-swap update; resolves `true` when `expectedVersion` matched. */
  trySpend(
    userIdHash: string,
    expectedVersion: number,
    next: { usageDay: string; dailyUsed: number; creditsSpent: number },
    now: string
  ): Promise<boolean>;
  refund(userIdHash: string, receipt: SpendReceipt, now: string): Promise<void>;
}

export class D1LedgerStore implements LedgerStore {
  constructor(private readonly database: D1Database | D1DatabaseSession) {}

  async get(userIdHash: string): Promise<LedgerRow | null> {
    return await this.database
      .prepare(
        `SELECT user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
                entitlement_verified_at, version
           FROM hosted_ai_ledger WHERE user_id_hash = ?`
      )
      .bind(userIdHash)
      .first<LedgerRow>();
  }

  async upsertEntitlement(
    userIdHash: string,
    snapshot: EntitlementSnapshot,
    verifiedAt: string
  ): Promise<LedgerRow> {
    // Timestamps are fixed-width `Date#toISOString()` values, so lexical
    // comparison in SQL orders them chronologically.
    const row = await this.database
      .prepare(
        `INSERT INTO hosted_ai_ledger (
           user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
           entitlement_verified_at, version, created_at, updated_at
         ) VALUES (?1, ?2, ?3, 0, ?4, 0, ?5, 0, ?5, ?5)
         ON CONFLICT(user_id_hash) DO UPDATE SET
           plan = CASE
             WHEN excluded.entitlement_verified_at >= hosted_ai_ledger.entitlement_verified_at
             THEN excluded.plan ELSE hosted_ai_ledger.plan END,
           credits_granted = MAX(hosted_ai_ledger.credits_granted, excluded.credits_granted),
           entitlement_verified_at = MAX(hosted_ai_ledger.entitlement_verified_at, excluded.entitlement_verified_at),
           version = hosted_ai_ledger.version + 1,
           updated_at = MAX(hosted_ai_ledger.updated_at, excluded.updated_at)
         RETURNING user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
                   entitlement_verified_at, version`
      )
      .bind(userIdHash, snapshot.plan, snapshot.creditsGranted, utcDayKey(new Date(verifiedAt)), verifiedAt)
      .first<LedgerRow>();
    if (!row) throw new Error("ledger_upsert_failed");
    return row;
  }

  async trySpend(
    userIdHash: string,
    expectedVersion: number,
    next: { usageDay: string; dailyUsed: number; creditsSpent: number },
    now: string
  ): Promise<boolean> {
    const result = await this.database
      .prepare(
        `UPDATE hosted_ai_ledger
            SET usage_day = ?1, daily_used = ?2, credits_spent = ?3,
                version = version + 1, updated_at = ?4
          WHERE user_id_hash = ?5 AND version = ?6`
      )
      .bind(next.usageDay, next.dailyUsed, next.creditsSpent, now, userIdHash, expectedVersion)
      .run();
    return (result.meta?.changes ?? 0) === 1;
  }

  async refund(userIdHash: string, receipt: SpendReceipt, now: string): Promise<void> {
    await this.database
      .prepare(
        `UPDATE hosted_ai_ledger
            SET daily_used = CASE WHEN usage_day = ?1 THEN MAX(0, daily_used - ?2) ELSE daily_used END,
                credits_spent = MAX(0, credits_spent - ?3),
                version = version + 1, updated_at = ?4
          WHERE user_id_hash = ?5`
      )
      .bind(receipt.day, receipt.fromDaily, receipt.fromCredits, now, userIdHash)
      .run();
  }
}

/**
 * Prunes idle ledger rows. Rows that have ever spent purchased credits are kept
 * indefinitely: `credits_granted` is re-derived from RevenueCat's lifetime
 * `non_subscriptions`, but `credits_spent` exists only here, so deleting such
 * a row would hand every previously consumed credit back on the next visit.
 */
export async function cleanupHostedAILedger(
  database: Pick<D1Database, "prepare">,
  now: Date = new Date()
): Promise<void> {
  const cutoff = new Date(now.getTime() - LEDGER_RETENTION_MS).toISOString();
  await database
    .prepare("DELETE FROM hosted_ai_ledger WHERE updated_at < ?1 AND credits_spent = 0")
    .bind(cutoff)
    .run();
}

export function utcDayKey(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export function quotaFromRow(row: LedgerRow, now: Date): QuotaSnapshot {
  const day = utcDayKey(now);
  const dailyUsed = row.usage_day === day ? row.daily_used : 0;
  return {
    plan: row.plan,
    day,
    dailyUsed,
    dailyLimit: HOSTED_AI_DAILY_LIMITS[row.plan],
    creditBank: Math.max(0, row.credits_granted - row.credits_spent),
  };
}

// MARK: RevenueCat verification

export class EntitlementLookupError extends Error {
  constructor(
    message: string,
    readonly upstreamStatus?: number
  ) {
    super(message);
    this.name = "EntitlementLookupError";
  }
}

interface RevenueCatSubscriberResponse {
  subscriber?: {
    entitlements?: Record<string, { expires_date?: string | null }>;
    non_subscriptions?: Record<string, Array<{ id?: string; purchase_date?: string }>>;
  };
}

/**
 * Parses a RevenueCat `GET /v1/subscribers/{id}` payload into the plan and the
 * total credits ever granted (deduplicated by transaction id).
 */
export function parseRevenueCatSubscriber(payload: unknown, now: Date): EntitlementSnapshot {
  const subscriber = (payload as RevenueCatSubscriberResponse | null)?.subscriber;
  if (!subscriber || typeof subscriber !== "object") {
    throw new EntitlementLookupError("revenuecat_invalid_payload");
  }

  const isActive = (entitlementId: string): boolean => {
    const entitlement = subscriber.entitlements?.[entitlementId];
    if (!entitlement) return false;
    if (entitlement.expires_date === null || entitlement.expires_date === undefined) return true;
    const expires = Date.parse(entitlement.expires_date);
    return Number.isFinite(expires) && expires > now.getTime();
  };

  let plan: HostedPlan = "none";
  if (isActive(PRO_ENTITLEMENT_ID)) plan = "pro";
  else if (isActive(PLUS_ENTITLEMENT_ID)) plan = "plus";

  const seenTransactions = new Set<string>();
  let creditsGranted = 0;
  for (const [productId, amount] of Object.entries(HOSTED_AI_CREDIT_PRODUCTS)) {
    const purchases = subscriber.non_subscriptions?.[productId];
    if (!Array.isArray(purchases)) continue;
    for (const purchase of purchases) {
      const transactionId = typeof purchase?.id === "string" ? purchase.id : null;
      if (!transactionId || seenTransactions.has(transactionId)) continue;
      seenTransactions.add(transactionId);
      creditsGranted += amount;
    }
  }

  return { plan, creditsGranted };
}

export async function fetchRevenueCatEntitlement(
  userId: string,
  apiKey: string,
  fetchImpl: typeof fetch,
  now: Date
): Promise<EntitlementSnapshot> {
  let response: Response;
  try {
    response = await fetchImpl(`https://api.revenuecat.com/v1/subscribers/${encodeURIComponent(userId)}`, {
      method: "GET",
      signal: AbortSignal.timeout(REVENUECAT_TIMEOUT_MS),
      headers: {
        Authorization: `Bearer ${apiKey}`,
        Accept: "application/json",
        "X-Platform": "ios",
      },
    });
  } catch (error) {
    throw new EntitlementLookupError(error instanceof Error ? error.name : "revenuecat_fetch_failed");
  }
  if (!response.ok) {
    throw new EntitlementLookupError("revenuecat_http_error", response.status);
  }
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new EntitlementLookupError("revenuecat_invalid_json", response.status);
  }
  return parseRevenueCatSubscriber(payload, now);
}

// MARK: Ledger operations

export interface LedgerContext {
  store: LedgerStore;
  now: () => Date;
  verify: (userId: string) => Promise<EntitlementSnapshot>;
  log?: (event: Record<string, unknown>) => void;
}

/**
 * Returns the ledger row for `userId`, re-verifying with RevenueCat when the
 * cached entitlement is older than its TTL (or when `forceRefresh` is set).
 * Falls back to a stale row for up to `ENTITLEMENT_STALE_GRACE_MS` if
 * RevenueCat is unavailable.
 */
export async function loadVerifiedLedger(
  context: LedgerContext,
  userId: string,
  userIdHash: string,
  forceRefresh = false
): Promise<LedgerRow> {
  const now = context.now();
  const existing = await context.store.get(userIdHash);
  const ageMs = existing ? now.getTime() - Date.parse(existing.entitlement_verified_at) : Number.POSITIVE_INFINITY;
  const fresh = existing !== null && Number.isFinite(ageMs) && ageMs < ENTITLEMENT_TTL_MS[existing.plan];
  if (fresh && !forceRefresh) return existing;

  try {
    const snapshot = await context.verify(userId);
    return await context.store.upsertEntitlement(userIdHash, snapshot, now.toISOString());
  } catch (error) {
    if (!(error instanceof EntitlementLookupError)) throw error;
    context.log?.({
      event: "hosted_ai_entitlement_lookup_failed",
      reason: error.message,
      upstreamStatus: error.upstreamStatus,
      hasCachedRow: existing !== null,
    });
    if (existing && Number.isFinite(ageMs) && ageMs < ENTITLEMENT_STALE_GRACE_MS) return existing;
    throw error;
  }
}

/**
 * Atomically deducts `cost` from the daily pool and then the credit bank.
 * Retries on version conflicts caused by concurrent requests from the same user.
 */
export async function spendFromLedger(
  context: LedgerContext,
  userIdHash: string,
  initialRow: LedgerRow,
  cost: number
): Promise<SpendOutcome> {
  let row = initialRow;
  for (let attempt = 0; attempt < SPEND_RETRY_ATTEMPTS; attempt += 1) {
    const now = context.now();
    const quota = quotaFromRow(row, now);
    const dailyRemaining = Math.max(0, quota.dailyLimit - quota.dailyUsed);
    const fromDaily = Math.min(dailyRemaining, cost);
    const fromCredits = cost - fromDaily;
    if (fromCredits > quota.creditBank) {
      return { status: "quota_exceeded", quota };
    }

    const next = {
      usageDay: quota.day,
      dailyUsed: quota.dailyUsed + fromDaily,
      creditsSpent: row.credits_spent + fromCredits,
    };
    const committed = await context.store.trySpend(userIdHash, row.version, next, now.toISOString());
    if (committed) {
      return {
        status: "spent",
        receipt: { fromDaily, fromCredits, day: quota.day },
        quota: {
          ...quota,
          dailyUsed: next.dailyUsed,
          creditBank: Math.max(0, row.credits_granted - next.creditsSpent),
        },
      };
    }

    const reloaded = await context.store.get(userIdHash);
    if (!reloaded) throw new Error("ledger_row_missing");
    row = reloaded;
  }
  throw new Error("ledger_contention");
}

export async function refundToLedger(
  context: LedgerContext,
  userIdHash: string,
  receipt: SpendReceipt
): Promise<void> {
  if (receipt.fromDaily === 0 && receipt.fromCredits === 0) return;
  await context.store.refund(userIdHash, receipt, context.now().toISOString());
}
