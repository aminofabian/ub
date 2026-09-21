-- Phase 2 expenses hub depth: source, paid_at, category taxonomy, approval.
-- journal_entry_id becomes nullable so pending approvals can exist before GL post.

ALTER TABLE expenses
  ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'manual' AFTER category_type,
  ADD COLUMN category_code VARCHAR(32) NULL AFTER source,
  ADD COLUMN paid_at TIMESTAMP NULL AFTER payment_method,
  ADD COLUMN approval_status VARCHAR(32) NOT NULL DEFAULT 'posted' AFTER include_in_cash_drawer,
  ADD COLUMN approved_by CHAR(36) NULL AFTER approval_status,
  ADD COLUMN approved_at TIMESTAMP NULL AFTER approved_by,
  ADD COLUMN approval_expires_at TIMESTAMP NULL AFTER approved_at;

-- Allow pending expenses without a journal yet (MySQL UNIQUE allows multiple NULLs).
ALTER TABLE expenses
  MODIFY COLUMN journal_entry_id CHAR(36) NULL;

CREATE INDEX idx_expenses_business_source ON expenses (business_id, source);
CREATE INDEX idx_expenses_business_category_code ON expenses (business_id, category_code);
CREATE INDEX idx_expenses_business_approval ON expenses (business_id, approval_status);

ALTER TABLE expense_schedules
  ADD COLUMN category_code VARCHAR(32) NULL AFTER category_type;

-- Sub-accounts under 6000 for cleaner P&L lines.
INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6010', 'Rent', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6010');

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6020', 'Utilities', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6020');

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6030', 'Salaries', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6030');

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6040', 'Transport', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6040');

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6050', 'Maintenance', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6050');

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '6060', 'Packaging & misc', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '6060');

-- Manage / approve permission (owner, admin, manager — not cashier).
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000092', 'finance.expenses.manage',
   'Manage recurring schedules and approve high-value expenses.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000092'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000092'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000092');
