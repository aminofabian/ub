-- Phase 5 hardening: settings write permission, reminder audit table, opt-out flag.

-- 1. Permission: credits.settings.write — admin tunables for loyalty/credit business settings.
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000076', 'credits.settings.write',
   'Edit loyalty earn/redeem tunables and other per-business credit settings.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000076'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000076');

-- 2. Opt-out flag for overdue debt reminders (PHASE_5_PLAN.md §Slice 6).
ALTER TABLE credit_accounts
  ADD COLUMN reminders_opt_out BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. Reminder audit — guarantees idempotency per (account, ISO week bucket).
CREATE TABLE credit_reminders (
  id                CHAR(36)     PRIMARY KEY,
  business_id       CHAR(36)     NOT NULL,
  credit_account_id CHAR(36)     NOT NULL,
  week_bucket       CHAR(8)      NOT NULL,
  channel           VARCHAR(16)  NOT NULL,
  outcome           VARCHAR(24)  NOT NULL,
  detail            VARCHAR(500) NULL,
  sent_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_credit_reminders_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_credit_reminders_account  FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  UNIQUE KEY uq_credit_reminders_account_week (credit_account_id, week_bucket)
);

CREATE INDEX idx_credit_reminders_business_sent_at ON credit_reminders (business_id, sent_at);
