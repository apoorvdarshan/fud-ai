-- Verified RevenueCat trial expiry; NULL means normal paid entitlement.
ALTER TABLE hosted_ai_ledger ADD COLUMN trial_expires_at TEXT;
