-- Phase 2 Slice 4 — supplier payments, allocations, AP aging (PHASE_2_PLAN.md §Slice 4).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000051', 'purchasing.payment.read',
   'View supplier payments and AP aging.'),
  ('11111111-0000-0000-0000-000000000052', 'purchasing.payment.write',
   'Record supplier payments and allocations.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000051'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000052'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000051'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000052'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000051'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000052');

ALTER TABLE suppliers
  ADD COLUMN prepayment_balance DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER payment_details;

CREATE TABLE supplier_payments (
  id              CHAR(36) PRIMARY KEY,
  business_id     CHAR(36) NOT NULL,
  supplier_id     CHAR(36) NOT NULL,
  paid_at         TIMESTAMP NOT NULL,
  payment_method  VARCHAR(32) NOT NULL,
  amount          DECIMAL(14,2) NOT NULL,
  credit_applied  DECIMAL(14,2) NOT NULL DEFAULT 0,
  reference       VARCHAR(128) NULL,
  notes           TEXT NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'posted',
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_sp_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_sp_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE INDEX idx_supplier_payments_supplier ON supplier_payments (business_id, supplier_id, paid_at);

CREATE TABLE supplier_payment_allocations (
  id                   CHAR(36) PRIMARY KEY,
  supplier_payment_id  CHAR(36) NOT NULL,
  supplier_invoice_id  CHAR(36) NOT NULL,
  amount               DECIMAL(14,2) NOT NULL,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_spa_payment FOREIGN KEY (supplier_payment_id) REFERENCES supplier_payments(id) ON DELETE CASCADE,
  CONSTRAINT fk_spa_invoice FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id)
);

CREATE INDEX idx_spa_invoice ON supplier_payment_allocations (supplier_invoice_id);
CREATE INDEX idx_spa_payment ON supplier_payment_allocations (supplier_payment_id);

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '1010', 'Operating cash', 'asset', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '1160', 'Supplier advances (prepayments)', 'asset', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;
