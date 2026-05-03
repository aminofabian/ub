-- Phase 6 Slice 1 — one-off expenses + journal posting (PHASE_6_PLAN.md §Slice 1).
-- Adds expenses table and permissions. Also seeds the operating expenses root account (6000)
-- for existing businesses (new businesses are covered by LedgerBootstrapService).

CREATE TABLE expenses (
  id                     CHAR(36)      PRIMARY KEY,
  business_id             CHAR(36)      NOT NULL,
  branch_id               CHAR(36)      NULL,
  expense_date            DATE          NOT NULL,
  name                   VARCHAR(255)  NOT NULL,
  category_type           VARCHAR(16)   NOT NULL, -- fixed | variable
  amount                 DECIMAL(14,2) NOT NULL,
  payment_method          VARCHAR(32)   NOT NULL, -- cash | mpesa_manual | bank
  include_in_cash_drawer  BOOLEAN       NOT NULL DEFAULT FALSE,
  receipt_s3_key          VARCHAR(500)  NULL,
  expense_ledger_account_id  CHAR(36)   NOT NULL,
  journal_entry_id        CHAR(36)      NOT NULL,
  created_by              CHAR(36)      NOT NULL,
  created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_expenses_journal_entry (journal_entry_id),
  CONSTRAINT fk_expenses_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_expenses_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_expenses_expense_account FOREIGN KEY (expense_ledger_account_id) REFERENCES ledger_accounts (id),
  CONSTRAINT fk_expenses_journal_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
  CONSTRAINT fk_expenses_created_by FOREIGN KEY (created_by) REFERENCES users (id)
);

CREATE INDEX idx_expenses_business_date ON expenses (business_id, expense_date);
CREATE INDEX idx_expenses_business_branch_date ON expenses (business_id, branch_id, expense_date);

-- Seed the operating expenses root account (6000) for existing businesses.
-- Parent/child category accounts can be added later; Slice 1 only needs a valid expense account.
INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6000', 'Operating expenses', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6000'
  );

-- Optional: seed a generic bank account for expense payments.
INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '1030', 'Bank account', 'asset', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '1030'
  );

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000090', 'finance.expenses.write',
   'Record operating expenses and post journal entries.'),
  ('11111111-0000-0000-0000-000000000091', 'finance.expenses.read',
   'View recorded operating expenses.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000090'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000091'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000090'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000091'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000090'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000091'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000090'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000091');

