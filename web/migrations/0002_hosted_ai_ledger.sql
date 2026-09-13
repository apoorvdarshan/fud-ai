-- Server-side entitlement cache + usage ledger for the hosted AI proxy.
-- One row per RevenueCat app user (stored as a SHA-256 hex hash).
-- See web/hosted-ai-ledger.ts and services/hosted-ai/README.md.

CREATE TABLE hosted_ai_ledger (
  user_id_hash TEXT PRIMARY KEY CHECK (length(user_id_hash) = 64),
  plan TEXT NOT NULL CHECK (plan IN ('none', 'plus', 'pro')),
  -- Lifetime credits granted, reconciled from RevenueCat non_subscriptions
  -- by transaction id (idempotent; survives reinstall/backup restore).
  credits_granted INTEGER NOT NULL DEFAULT 0 CHECK (credits_granted >= 0),
  credits_spent INTEGER NOT NULL DEFAULT 0 CHECK (credits_spent >= 0),
  -- UTC day (YYYY-MM-DD) the daily counter applies to.
  usage_day TEXT NOT NULL CHECK (length(usage_day) = 10),
  daily_used INTEGER NOT NULL DEFAULT 0 CHECK (daily_used >= 0),
  entitlement_verified_at TEXT NOT NULL,
  -- Optimistic-concurrency counter bumped on every write.
  version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX hosted_ai_ledger_updated_idx ON hosted_ai_ledger(updated_at);
