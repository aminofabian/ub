-- Phase 7 Slice 3 — mv_supplier_monthly (PHASE_7_PLAN.md §Slice 3).
-- MySQL summary table: posted supplier invoices + line qty + wastage from stock_movements
-- (movement_type = wastage) attributed via inventory_batches.supplier_id.

CREATE TABLE mv_supplier_monthly (
  business_id    CHAR(36)       NOT NULL,
  supplier_id    CHAR(36)       NOT NULL,
  calendar_month DATE           NOT NULL,
  spend          DECIMAL(18, 2) NOT NULL,
  qty            DECIMAL(18, 4) NOT NULL,
  invoice_count  BIGINT         NOT NULL,
  wastage_qty    DECIMAL(18, 4) NOT NULL DEFAULT 0,
  refreshed_at   TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (business_id, supplier_id, calendar_month)
);

CREATE INDEX idx_mvsup_business_month ON mv_supplier_monthly (business_id, calendar_month);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000102', 'reports.suppliers.read',
   'View supplier spend reports (MV-backed + current-month OLTP).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000102'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000102'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000102'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000102');
