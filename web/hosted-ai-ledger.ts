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
 *
 * The live voice relay meters the same row in 15-second blocks:
 *
 *   monthly voice allowance (UTC calendar month) → credit bank (1 per block) → 4402
 */

/**
 * Consumable credit packs, keyed by the store product id RevenueCat reports in
 * `non_subscriptions`. Google Play caps product ids at 40 characters, so the
 * Play packs cannot reuse the App Store ids. Must match store/catalog/products.json
 * (enforced by a test).
 */
export const HOSTED_AI_CREDIT_PRODUCTS: Readonly<Record<string, number>> = {
  // App Store
  "com.apoorvdarshan.calorietracker.credits.50": 50,
  "com.apoorvdarshan.calorietracker.credits.150": 150,
  "com.apoorvdarshan.calorietracker.credits.400": 400,
  // Google Play
  credits_50: 50,
  credits_150: 150,
  credits_400: 400,
};

export const HOSTED_AI_DAILY_LIMITS: Readonly<Record<HostedPlan, number>> = {
  none: 0,
  plus: 30,
  pro: 60,
};

/** One metered voice block; each block is charged when it starts. */
export const VOICE_BLOCK_SECONDS = 15;

/** Credits a voice block costs once the monthly allowance is used up (4 a minute). */
export const VOICE_CREDITS_PER_BLOCK = 1;

/** Monthly voice allowance per plan in seconds, reset each UTC calendar month. */
export const HOSTED_AI_VOICE_MONTHLY_SECONDS: Readonly<Record<HostedPlan, number>> = {
  none: 0,
  plus: 20 * 60,
  pro: 100 * 60,
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
  trial_expires_at?: string | null;
  credits_granted: number;
  credits_spent: number;
  usage_day: string;
  daily_used: number;
  entitlement_verified_at: string;
  version: number;
  /** UTC month (YYYY-MM) `voice_seconds_used` applies to; '' before the first voice block. */
  voice_month: string;
  /** Seconds drawn from the monthly voice allowance in `voice_month`. */
  voice_seconds_used: number;
}

export interface EntitlementSnapshot {
  plan: HostedPlan;
  creditsGranted: number;
  trialExpiresAt?: string | null;
}

export interface QuotaSnapshot {
  plan: HostedPlan;
  day: string;
  dailyUsed: number;
  dailyLimit: number;
  creditBank: number;
}

export interface SpendReceipt {
  /** Unique per spend; refunds are keyed on it so they apply at most once. */
  id: string;
  fromDaily: number;
  fromCredits: number;
  day: string;
}

export type SpendOutcome =
  | { status: "spent"; receipt: SpendReceipt; quota: QuotaSnapshot }
  | { status: "quota_exceeded"; quota: QuotaSnapshot };

/** Voice balance for the current UTC month, as returned by `GET /quota`. */
export interface VoiceQuotaSnapshot {
  month: string;
  /** Seconds drawn from this month's allowance (credit-paid blocks are not counted here). */
  secondsUsed: number;
  secondsLimit: number;
  /** The same credit bank hosted actions use. */
  creditBank: number;
}

export type VoiceSpendOutcome =
  | {
      status: "spent";
      source: "allowance" | "credits";
      voice: VoiceQuotaSnapshot;
      /** The row as committed, so the next block can start its compare-and-swap from it. */
      row: LedgerRow;
    }
  | { status: "quota_exceeded"; voice: VoiceQuotaSnapshot };

/** Storage abstraction so tests can run against an in-memory ledger. */
export interface LedgerStore {
  get(userIdHash: string): Promise<LedgerRow | null>;
  /** A stable receipt makes retries after an uncertain write outcome idempotent. */
  getVoiceSpend(userIdHash: string, receiptId: string): Promise<"allowance" | "credits" | null>;
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
  /**
   * Compare-and-swap update of the voice counters and `credits_spent` (which
   * grows when a voice block is paid from the credit bank); resolves `true`
   * when `expectedVersion` matched.
   */
  trySpendVoice(
    userIdHash: string,
    expectedVersion: number,
    next: { voiceMonth: string; voiceSecondsUsed: number; creditsSpent: number },
    now: string,
    receipt: { id: string; source: "allowance" | "credits" }
  ): Promise<boolean>;
  /**
   * Reverses `receipt`. Must be idempotent: applying the same receipt again
   * (a retry after a write that committed but reported an error) is a no-op.
   */
  refund(userIdHash: string, receipt: SpendReceipt, now: string): Promise<void>;
}

/** Applied-refund markers older than this are pruned by the scheduled job. */
export const REFUND_MARKER_RETENTION_MS = 30 * 24 * 60 * 60 * 1000;

export class D1LedgerStore implements LedgerStore {
  constructor(private readonly database: D1Database | D1DatabaseSession) {}

  async getVoiceSpend(userIdHash: string, receiptId: string): Promise<"allowance" | "credits" | null> {
    const receipt = await this.database
      .prepare("SELECT source FROM hosted_ai_voice_spends WHERE receipt_id = ?1 AND user_id_hash = ?2")
      .bind(receiptId, userIdHash)
      .first<{ source: "allowance" | "credits" }>();
    return receipt?.source ?? null;
  }

  async get(userIdHash: string): Promise<LedgerRow | null> {
    return await this.database
      .prepare(
        `SELECT user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
                entitlement_verified_at, version, voice_month, voice_seconds_used, trial_expires_at
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
           entitlement_verified_at, version, created_at, updated_at, trial_expires_at
         ) VALUES (?1, ?2, ?3, 0, ?4, 0, ?5, 0, ?5, ?5, ?6)
         ON CONFLICT(user_id_hash) DO UPDATE SET
           plan = CASE
             WHEN excluded.entitlement_verified_at >= hosted_ai_ledger.entitlement_verified_at
             THEN excluded.plan ELSE hosted_ai_ledger.plan END,
           trial_expires_at = CASE
             WHEN excluded.entitlement_verified_at >= hosted_ai_ledger.entitlement_verified_at
             THEN excluded.trial_expires_at ELSE hosted_ai_ledger.trial_expires_at END,
           credits_granted = MAX(hosted_ai_ledger.credits_granted, excluded.credits_granted),
           entitlement_verified_at = MAX(hosted_ai_ledger.entitlement_verified_at, excluded.entitlement_verified_at),
           version = hosted_ai_ledger.version + 1,
           updated_at = MAX(hosted_ai_ledger.updated_at, excluded.updated_at)
         RETURNING user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
                   entitlement_verified_at, version, voice_month, voice_seconds_used, trial_expires_at`
      )
      .bind(userIdHash, snapshot.plan, snapshot.creditsGranted, utcDayKey(new Date(verifiedAt)), verifiedAt, snapshot.trialExpiresAt ?? null)
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

  async trySpendVoice(
    userIdHash: string,
    expectedVersion: number,
    next: { voiceMonth: string; voiceSecondsUsed: number; creditsSpent: number },
    now: string,
    receipt: { id: string; source: "allowance" | "credits" }
  ): Promise<boolean> {
    // D1 batches are atomic. Only the update that matched this version writes
    // a receipt; a retry after commit cannot apply that receipt a second time.
    const results = await this.database.batch([
      this.database.prepare(
        `UPDATE hosted_ai_ledger
            SET voice_month = ?1, voice_seconds_used = ?2, credits_spent = ?3,
                version = version + 1, updated_at = ?4
          WHERE user_id_hash = ?5 AND version = ?6
            AND NOT EXISTS (SELECT 1 FROM hosted_ai_voice_spends WHERE receipt_id = ?7)`
      )
      .bind(next.voiceMonth, next.voiceSecondsUsed, next.creditsSpent, now, userIdHash, expectedVersion, receipt.id),
      this.database.prepare(
        `INSERT INTO hosted_ai_voice_spends (receipt_id, user_id_hash, source, created_at)
         SELECT ?1, ?2, ?3, ?4 WHERE changes() = 1`
      ).bind(receipt.id, userIdHash, receipt.source, now),
    ]);
    return (results[0]?.meta?.changes ?? 0) === 1;
  }

  /**
   * The ledger update and the applied-refund marker are written in one D1
   * batch, which runs as a single transaction. The update is conditional on
   * the marker not existing, so if the transaction committed but the error
   * was reported after the fact, the retry matches zero rows instead of
   * crediting the user a second time. If it rolled back, neither statement
   * took effect and the retry applies the refund normally.
   */
  async refund(userIdHash: string, receipt: SpendReceipt, now: string): Promise<void> {
    await this.database.batch([
      this.database
        .prepare(
          `UPDATE hosted_ai_ledger
              SET daily_used = CASE WHEN usage_day = ?1 THEN MAX(0, daily_used - ?2) ELSE daily_used END,
                  credits_spent = MAX(0, credits_spent - ?3),
                  version = version + 1, updated_at = ?4
            WHERE user_id_hash = ?5
              AND NOT EXISTS (SELECT 1 FROM hosted_ai_refunds WHERE receipt_id = ?6)`
        )
        .bind(receipt.day, receipt.fromDaily, receipt.fromCredits, now, userIdHash, receipt.id),
      this.database
        .prepare(
          `INSERT OR IGNORE INTO hosted_ai_refunds (receipt_id, user_id_hash, created_at)
           VALUES (?1, ?2, ?3)`
        )
        .bind(receipt.id, userIdHash, now),
    ]);
  }
}

/**
 * Prunes idle ledger rows and stale refund markers. Ledger rows that have
 * ever spent purchased credits are kept indefinitely: `credits_granted` is
 * re-derived from RevenueCat's lifetime `non_subscriptions`, but
 * `credits_spent` exists only here, so deleting such a row would hand every
 * previously consumed credit back on the next visit (voice blocks paid with
 * credits count too: they raise `credits_spent`). Every voice block bumps
 * `updated_at`, and the voice allowance resets monthly, so an idle row never
 * carries voice usage worth keeping. Refund markers only need to outlive the
 * refund retry window (seconds), so they are pruned after
 * `REFUND_MARKER_RETENTION_MS`.
 */
export async function cleanupHostedAILedger(
  database: Pick<D1Database, "prepare">,
  now: Date = new Date()
): Promise<void> {
  const ledgerCutoff = new Date(now.getTime() - LEDGER_RETENTION_MS).toISOString();
  await database
    .prepare("DELETE FROM hosted_ai_ledger WHERE updated_at < ?1 AND credits_spent = 0")
    .bind(ledgerCutoff)
    .run();
  const refundCutoff = new Date(now.getTime() - REFUND_MARKER_RETENTION_MS).toISOString();
  await database.prepare("DELETE FROM hosted_ai_refunds WHERE created_at < ?1").bind(refundCutoff).run();
  await database.prepare("DELETE FROM hosted_ai_voice_spends WHERE created_at < ?1").bind(refundCutoff).run();
}

export function utcDayKey(date: Date): string {
  return date.toISOString().slice(0, 10);
}

export function utcMonthKey(date: Date): string {
  return date.toISOString().slice(0, 7);
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
    dailyLimit: row.plan !== "none" && row.trial_expires_at ? 20 : HOSTED_AI_DAILY_LIMITS[row.plan],
    creditBank: Math.max(0, row.credits_granted - row.credits_spent),
  };
}

export function voiceQuotaFromRow(row: LedgerRow, now: Date): VoiceQuotaSnapshot {
  // Seven-character period key preserves the existing D1 constraint. TYYMMDD
  // is a UTC trial day; YYYY-MM remains the paid calendar month.
  const month = row.trial_expires_at ? `T${utcDayKey(now).slice(2).replaceAll("-", "")}` : utcMonthKey(now);
  return {
    month,
    secondsUsed: row.voice_month === month ? row.voice_seconds_used : 0,
    secondsLimit: row.trial_expires_at
      ? ({ none: 0, plus: 120, pro: 300 }[row.plan])
      : HOSTED_AI_VOICE_MONTHLY_SECONDS[row.plan],
    creditBank: Math.max(0, row.credits_granted - row.credits_spent),
  };
}

/** Whole voice blocks the balance can still pay for: allowance first, then credits. */
export function voiceBlocksRemaining(voice: VoiceQuotaSnapshot): number {
  const allowanceSeconds = Math.max(0, voice.secondsLimit - voice.secondsUsed);
  return (
    Math.floor(allowanceSeconds / VOICE_BLOCK_SECONDS) + Math.floor(voice.creditBank / VOICE_CREDITS_PER_BLOCK)
  );
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
    entitlements?: Record<string, { expires_date?: string | null; product_identifier?: string }>;
    subscriptions?: Record<string, { period_type?: string; expires_date?: string | null }>;
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

  // Match the selected entitlement to its verified subscription. Never accept
  // a trial flag from the device or infer conversion from the clock alone.
  const entitlement = subscriber.entitlements?.[plan];
  const subscription = entitlement?.product_identifier
    ? subscriber.subscriptions?.[entitlement.product_identifier] : undefined;
  if (plan !== "none" && subscription?.period_type === "trial") {
    const expires = subscription.expires_date ?? entitlement?.expires_date;
    if (!expires || !Number.isFinite(Date.parse(expires))) {
      throw new EntitlementLookupError("revenuecat_invalid_trial_expiry");
    }
    return { plan, creditsGranted, trialExpiresAt: expires };
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
  if (fresh && !forceRefresh && !(existing.trial_expires_at && now.getTime() >= Date.parse(existing.trial_expires_at))) return existing;

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
        receipt: { id: crypto.randomUUID(), fromDaily, fromCredits, day: quota.day },
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

/**
 * Atomically pays for one voice block: `VOICE_BLOCK_SECONDS` from this UTC
 * month's allowance when a whole block is left, otherwise
 * `VOICE_CREDITS_PER_BLOCK` from the credit bank. A new month starts the
 * allowance counter from zero. Same compare-and-swap and retry as
 * `spendFromLedger`, so voice sessions and hosted actions running at once for
 * one user cannot double-spend. Blocks are never refunded: each is charged when
 * it starts and covers the provider time that follows.
 */
export async function spendVoiceBlock(
  context: LedgerContext,
  userIdHash: string,
  initialRow: LedgerRow,
  receiptId: string = crypto.randomUUID()
): Promise<VoiceSpendOutcome> {
  let row = initialRow;
  for (let attempt = 0; attempt < SPEND_RETRY_ATTEMPTS; attempt += 1) {
    const now = context.now();
    const paidSource = await context.store.getVoiceSpend(userIdHash, receiptId);
    if (paidSource !== null) {
      const paidRow = await context.store.get(userIdHash);
      if (!paidRow) throw new Error("ledger_row_missing");
      return { status: "spent", source: paidSource, voice: voiceQuotaFromRow(paidRow, now), row: paidRow };
    }
    const voice = voiceQuotaFromRow(row, now);
    let source: "allowance" | "credits";
    let next: { voiceMonth: string; voiceSecondsUsed: number; creditsSpent: number };
    if (voice.secondsLimit - voice.secondsUsed >= VOICE_BLOCK_SECONDS) {
      source = "allowance";
      next = {
        voiceMonth: voice.month,
        voiceSecondsUsed: voice.secondsUsed + VOICE_BLOCK_SECONDS,
        creditsSpent: row.credits_spent,
      };
    } else if (voice.creditBank >= VOICE_CREDITS_PER_BLOCK) {
      source = "credits";
      next = {
        voiceMonth: voice.month,
        voiceSecondsUsed: voice.secondsUsed,
        creditsSpent: row.credits_spent + VOICE_CREDITS_PER_BLOCK,
      };
    } else {
      return { status: "quota_exceeded", voice };
    }

    const committed = await context.store.trySpendVoice(userIdHash, row.version, next, now.toISOString(), { id: receiptId, source });
    if (committed) {
      const updated: LedgerRow = {
        ...row,
        voice_month: next.voiceMonth,
        voice_seconds_used: next.voiceSecondsUsed,
        credits_spent: next.creditsSpent,
        version: row.version + 1,
      };
      return { status: "spent", source, voice: voiceQuotaFromRow(updated, now), row: updated };
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
