import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { describe, expect, it } from "vitest";
import {
  D1LedgerStore,
  LEDGER_RETENTION_MS,
  cleanupHostedAILedger,
  spendFromLedger,
  spendVoiceBlock,
  type LedgerContext,
  type LedgerRow,
} from "../hosted-ai-ledger";

type SqlValue = string | number | null;

/** The slice of the D1 API the ledger uses, backed by an in-memory SQLite database. */
function sqliteD1(db: DatabaseSync): D1Database {
  const runOne = (sql: string, params: SqlValue[]) => {
    const result = db.prepare(sql).run(...params);
    return { success: true, meta: { changes: Number(result.changes) } };
  };
  return {
    prepare: (sql: string) => ({
      bind: (...params: SqlValue[]) => ({
        sql,
        params,
        first: async () => db.prepare(sql).get(...params) ?? null,
        run: async () => runOne(sql, params),
      }),
    }),
    // D1 runs a batch as one transaction.
    batch: async (statements: Array<{ sql: string; params: SqlValue[] }>) => {
      db.exec("BEGIN");
      try {
        const results = statements.map((statement) => runOne(statement.sql, statement.params));
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
  } as unknown as D1Database;
}

function database(migrations: string[]): DatabaseSync {
  const db = new DatabaseSync(":memory:");
  for (const file of migrations) db.exec(readFileSync(`migrations/${file}`, "utf8"));
  return db;
}

const ALL_MIGRATIONS = ["0002_hosted_ai_ledger.sql", "0003_hosted_ai_refunds.sql", "0004_hosted_ai_voice.sql", "0005_hosted_ai_voice_spends.sql", "0006_hosted_ai_trials.sql"];
const HASH = "a".repeat(64);
const NOW = new Date("2026-09-13T12:00:00.000Z");

function context(store: D1LedgerStore, now: () => Date = () => NOW): LedgerContext {
  return { store, now, verify: async () => ({ plan: "plus", creditsGranted: 0 }) };
}

describe("hosted AI ledger on SQLite (D1 schema and queries)", () => {
  it("0004 adds the voice counters with defaults and keeps existing rows readable", async () => {
    const db = database(ALL_MIGRATIONS.slice(0, 2));
    db.prepare(
      `INSERT INTO hosted_ai_ledger (user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
         entitlement_verified_at, version, created_at, updated_at)
       VALUES (?, 'pro', 150, 20, '2026-09-13', 4, '2026-09-13T00:00:00.000Z', 7, '2026-09-01T00:00:00.000Z', '2026-09-13T00:00:00.000Z')`
    ).run(HASH);
    db.exec(readFileSync("migrations/0004_hosted_ai_voice.sql", "utf8"));
    db.exec(readFileSync("migrations/0006_hosted_ai_trials.sql", "utf8"));

    const row = await new D1LedgerStore(sqliteD1(db)).get(HASH);
    expect({ ...row }).toEqual({
      user_id_hash: HASH,
      plan: "pro",
      credits_granted: 150,
      credits_spent: 20,
      usage_day: "2026-09-13",
      daily_used: 4,
      entitlement_verified_at: "2026-09-13T00:00:00.000Z",
      version: 7,
      trial_expires_at: null,
      voice_month: "",
      voice_seconds_used: 0,
    });
  });

  it("persists trial metering and does not let an old refresh undo conversion", async () => {
    const store = new D1LedgerStore(sqliteD1(database(ALL_MIGRATIONS)));
    const expires = "2026-09-16T12:00:00.000Z";
    let row = await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 50, trialExpiresAt: expires }, NOW.toISOString());
    for (let i = 0; i < 9; i++) {
      const result = await spendVoiceBlock(context(store), HASH, row);
      expect(result.status).toBe("spent");
      if (result.status === "spent") row = result.row;
    }
    expect(await store.get(HASH)).toMatchObject({ trial_expires_at: expires, voice_seconds_used: 120, credits_spent: 1 });
    await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 50 }, "2026-09-16T12:00:01.000Z");
    const converted = await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 50, trialExpiresAt: expires }, NOW.toISOString());
    expect(converted.trial_expires_at).toBeNull();
    expect(converted.credits_spent).toBe(1);
  });

  it("round-trips the voice counters with a version compare-and-swap", async () => {
    const store = new D1LedgerStore(sqliteD1(database(ALL_MIGRATIONS)));
    const created = await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 50 }, NOW.toISOString());
    expect(created).toMatchObject({ version: 0, voice_month: "", voice_seconds_used: 0 });

    const next = { voiceMonth: "2026-09", voiceSecondsUsed: 15, creditsSpent: 0 };
    const receipt = { id: crypto.randomUUID(), source: "allowance" as const };
    await expect(store.trySpendVoice(HASH, 0, next, NOW.toISOString(), receipt)).resolves.toBe(true);
    await expect(store.trySpendVoice(HASH, 0, { ...next, voiceSecondsUsed: 30 }, NOW.toISOString(), receipt)).resolves.toBe(false);
    expect(await store.get(HASH)).toMatchObject({ voice_month: "2026-09", voice_seconds_used: 15, version: 1 });

    // A later RevenueCat re-verification returns the voice counters untouched.
    const reverified = await store.upsertEntitlement(HASH, { plan: "pro", creditsGranted: 50 }, "2026-09-13T12:30:00.000Z");
    expect(reverified).toMatchObject({ plan: "pro", voice_month: "2026-09", voice_seconds_used: 15, version: 2 });
  });

  it("records a voice debit and receipt atomically and replays it without another debit", async () => {
    const db = database(ALL_MIGRATIONS);
    const store = new D1LedgerStore(sqliteD1(db));
    const initial = await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 0 }, NOW.toISOString());
    const receiptId = crypto.randomUUID();
    await spendVoiceBlock(context(store), HASH, initial, receiptId);
    await spendVoiceBlock(context(store), HASH, initial, receiptId);
    expect(await store.get(HASH)).toMatchObject({ voice_seconds_used: 15, version: 1 });
    expect(await store.getVoiceSpend(HASH, receiptId)).toBe("allowance");
    expect(await store.getVoiceSpend("b".repeat(64), receiptId)).toBeNull();
    expect(db.prepare("SELECT count(*) AS count FROM hosted_ai_voice_spends").get()?.count).toBe(1);

    // A failed receipt insert rolls its ledger debit back too.
    await expect(store.trySpendVoice(HASH, 1, { voiceMonth: "2026-09", voiceSecondsUsed: 30, creditsSpent: 0 }, NOW.toISOString(), { id: "invalid", source: "allowance" })).rejects.toThrow();
    expect(await store.get(HASH)).toMatchObject({ voice_seconds_used: 15, version: 1 });
  });

  it("pays voice blocks from the allowance, then the shared credit bank, then refuses", async () => {
    const db = database(ALL_MIGRATIONS);
    const store = new D1LedgerStore(sqliteD1(db));
    await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 2 }, NOW.toISOString());
    db.prepare("UPDATE hosted_ai_ledger SET voice_month = '2026-09', voice_seconds_used = 1185 WHERE user_id_hash = ?").run(HASH);
    const ledger = context(store);

    let row = (await store.get(HASH))!;
    const sources: string[] = [];
    for (let block = 0; block < 3; block += 1) {
      const outcome = await spendVoiceBlock(ledger, HASH, row);
      expect(outcome.status).toBe("spent");
      if (outcome.status !== "spent") return;
      sources.push(outcome.source);
      row = outcome.row;
    }
    expect(sources).toEqual(["allowance", "credits", "credits"]);
    await expect(spendVoiceBlock(ledger, HASH, row)).resolves.toMatchObject({ status: "quota_exceeded" });
    expect(await store.get(HASH)).toMatchObject({ voice_seconds_used: 1200, credits_spent: 2, version: 3 });

    // Hosted actions still have their own daily pool after voice used the credits.
    const action = await spendFromLedger(ledger, HASH, (await store.get(HASH))!, 1);
    expect(action).toMatchObject({ status: "spent", quota: { dailyUsed: 1, creditBank: 0 } });
  });

  it("re-reads a stale row instead of overwriting a concurrent charge", async () => {
    const store = new D1LedgerStore(sqliteD1(database(ALL_MIGRATIONS)));
    const stale = await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 0 }, NOW.toISOString());
    await spendVoiceBlock(context(store), HASH, stale);
    const second = await spendVoiceBlock(context(store), HASH, stale);
    expect(second).toMatchObject({ status: "spent", voice: { secondsUsed: 30 } });
    expect(await store.get(HASH)).toMatchObject({ voice_seconds_used: 30, version: 2 });
  });

  it("starts the allowance over in a new UTC month", async () => {
    const db = database(ALL_MIGRATIONS);
    const store = new D1LedgerStore(sqliteD1(db));
    await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 0 }, NOW.toISOString());
    db.prepare("UPDATE hosted_ai_ledger SET voice_month = '2026-08', voice_seconds_used = 1200 WHERE user_id_hash = ?").run(HASH);
    const outcome = await spendVoiceBlock(context(store), HASH, (await store.get(HASH))!);
    expect(outcome).toMatchObject({ status: "spent", source: "allowance", voice: { month: "2026-09", secondsUsed: 15 } });
    expect(await store.get(HASH)).toMatchObject({ voice_month: "2026-09", voice_seconds_used: 15 });
  });

  it("rejects malformed voice values at the schema level", () => {
    const db = database(ALL_MIGRATIONS);
    const store = new D1LedgerStore(sqliteD1(db));
    return store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 0 }, NOW.toISOString()).then(() => {
      expect(() => db.prepare("UPDATE hosted_ai_ledger SET voice_month = '2026-9' WHERE user_id_hash = ?").run(HASH)).toThrow(
        /CHECK constraint failed/
      );
      expect(() => db.prepare("UPDATE hosted_ai_ledger SET voice_seconds_used = -15 WHERE user_id_hash = ?").run(HASH)).toThrow(
        /CHECK constraint failed/
      );
    });
  });

  it("retention cleanup still prunes idle rows and keeps rows that spent credits on voice", async () => {
    const db = database(ALL_MIGRATIONS);
    const insert = db.prepare(
      `INSERT INTO hosted_ai_ledger (user_id_hash, plan, credits_granted, credits_spent, usage_day, daily_used,
         entitlement_verified_at, version, created_at, updated_at, voice_month, voice_seconds_used)
       VALUES (?, 'plus', ?, ?, '2026-01-01', 0, ?, 0, ?, ?, '2026-01', ?)`
    );
    const idle = new Date(NOW.getTime() - LEDGER_RETENTION_MS - 1000).toISOString();
    const recent = new Date(NOW.getTime() - 1000).toISOString();
    insert.run("1".repeat(64), 0, 0, idle, idle, idle, 600); // idle, voice allowance only → pruned
    insert.run("2".repeat(64), 50, 4, idle, idle, idle, 1200); // idle, 4 credits spent on voice → kept
    insert.run("3".repeat(64), 0, 0, recent, recent, recent, 300); // active → kept

    await cleanupHostedAILedger(sqliteD1(db), NOW);
    const remaining = db.prepare("SELECT user_id_hash FROM hosted_ai_ledger ORDER BY user_id_hash").all();
    expect(remaining.map((row) => String(row.user_id_hash)[0])).toEqual(["2", "3"]);
  });

  it("every voice charge refreshes updated_at so active rows are never pruned", async () => {
    const db = database(ALL_MIGRATIONS);
    const store = new D1LedgerStore(sqliteD1(db));
    await store.upsertEntitlement(HASH, { plan: "plus", creditsGranted: 0 }, "2026-01-01T00:00:00.000Z");
    const row = (await store.get(HASH)) as LedgerRow;
    await spendVoiceBlock(context(store), HASH, row);
    const stored = db.prepare("SELECT updated_at FROM hosted_ai_ledger WHERE user_id_hash = ?").get(HASH);
    expect(stored?.updated_at).toBe(NOW.toISOString());
  });
});
