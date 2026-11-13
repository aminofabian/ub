-- Staff commission, tips and payouts (ADR 0014 + amendment, tickets T7/T8).
--
-- The commission plan is three nullable columns on staff_profiles (no plan table: every earning
-- snapshots the rule it used, so no history is needed).
--
-- staff_ledger_entries is the one new table: a signed per-staff ledger. Commission, tips and
-- adjustments are positive or negative; a payout is a negative row. A staff member's balance is
-- the sum of their rows. No existing table can hold this: payslips and salary advances are tied
-- to monthly payroll and to staff with a login, cash drawouts to a shift, wallet rows to a
-- customer, and journal lines carry no staff dimension.
--
-- bucket says which liability the row moves: COMMISSION -> commissions payable (2300),
-- TIPS -> tips payable (2310). The ledger must always agree with those two accounts.
-- dedupe_key makes every write idempotent (a replayed sale, a double-tapped payout).

ALTER TABLE staff_profiles
  ADD COLUMN commission_type VARCHAR(16) NULL,
  ADD COLUMN commission_rate DECIMAL(14,2) NULL,
  ADD COLUMN product_commission_rate DECIMAL(5,2) NULL;

CREATE TABLE IF NOT EXISTS staff_ledger_entries (
  id                CHAR(36)      NOT NULL PRIMARY KEY,
  business_id       CHAR(36)      NOT NULL,
  branch_id         CHAR(36)      NULL,
  staff_profile_id  CHAR(36)      NOT NULL,
  kind              VARCHAR(16)   NOT NULL,
  bucket            VARCHAR(16)   NOT NULL,
  amount            DECIMAL(14,2) NOT NULL,
  base_amount       DECIMAL(14,2) NULL,
  rule              VARCHAR(64)   NULL,
  source            VARCHAR(16)   NULL,
  sale_id           CHAR(36)      NULL,
  sale_item_id      CHAR(36)      NULL,
  reverses_id       CHAR(36)      NULL,
  payout_method     VARCHAR(16)   NULL,
  reference         VARCHAR(191)  NULL,
  shift_id          CHAR(36)      NULL,
  reason            VARCHAR(500)  NULL,
  journal_entry_id  CHAR(36)      NULL,
  dedupe_key        VARCHAR(191)  NOT NULL,
  created_by        CHAR(36)      NULL,
  occurred_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_staff_ledger_dedupe (business_id, dedupe_key),
  KEY idx_staff_ledger_staff_time (business_id, staff_profile_id, occurred_at),
  KEY idx_staff_ledger_sale (business_id, sale_id),
  KEY idx_staff_ledger_sale_item (business_id, sale_item_id),
  KEY idx_staff_ledger_reverses (business_id, reverses_id),
  CONSTRAINT fk_staff_ledger_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_staff_ledger_profile FOREIGN KEY (staff_profile_id) REFERENCES staff_profiles (id)
);

-- INSERT IGNORE so a re-run after a partial failure does not die on rows already written.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e41', 'commissions.plans.manage',
   'Set how each staff member earns commission.'),
  ('6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e42', 'commissions.read',
   'See what every staff member has earned, been paid and is owed.'),
  ('6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43', 'commissions.read_own',
   'See your own earnings, payouts and balance.'),
  ('6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e44', 'commissions.adjust',
   'Add or deduct an amount on a staff member''s earnings, with a reason.'),
  ('6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e45', 'commissions.payout',
   'Record money paid to staff against their earnings, and reverse a payout.');

-- Owner: 22222222-0000-0000-0000-000000000001, Admin: 22222222-0000-0000-0000-000000000002
INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e41'),
  ('22222222-0000-0000-0000-000000000001', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e42'),
  ('22222222-0000-0000-0000-000000000001', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43'),
  ('22222222-0000-0000-0000-000000000001', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e44'),
  ('22222222-0000-0000-0000-000000000001', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e45'),
  ('22222222-0000-0000-0000-000000000002', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e41'),
  ('22222222-0000-0000-0000-000000000002', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e42'),
  ('22222222-0000-0000-0000-000000000002', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43'),
  ('22222222-0000-0000-0000-000000000002', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e44'),
  ('22222222-0000-0000-0000-000000000002', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e45');
