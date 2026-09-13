-- Applied-refund markers for the hosted AI ledger.
-- Every spend carries a unique receipt id; a refund is written together with
-- its marker in one transaction and the ledger update is conditional on the
-- marker not existing yet, so retrying a refund can never credit twice.
-- See web/hosted-ai-ledger.ts (D1LedgerStore.refund).

CREATE TABLE hosted_ai_refunds (
  receipt_id TEXT PRIMARY KEY CHECK (length(receipt_id) = 36),
  user_id_hash TEXT NOT NULL CHECK (length(user_id_hash) = 64),
  created_at TEXT NOT NULL
);

CREATE INDEX hosted_ai_refunds_created_idx ON hosted_ai_refunds(created_at);
