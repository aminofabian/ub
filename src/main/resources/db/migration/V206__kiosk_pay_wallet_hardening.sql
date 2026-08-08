-- Kiosk Pay: optimistic locking on wallet balances so concurrent credits /
-- holds cannot lose updates (mirrors @Version used elsewhere in the codebase).
ALTER TABLE kiosk_pay_accounts
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
