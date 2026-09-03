-- Staff can freeze a customer from taking more on tab without wiping the
-- open balance. Existing debt still collects; new customer_credit tender is refused.
ALTER TABLE credit_accounts
  ADD COLUMN credit_suspended BOOLEAN NOT NULL DEFAULT FALSE AFTER credit_limit;
