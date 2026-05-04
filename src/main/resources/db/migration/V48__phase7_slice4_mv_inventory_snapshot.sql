-- Phase 7 Slice 4 — mv_inventory_snapshot (PHASE_7_PLAN.md §Slice 4).
-- Rolling snapshot per tenant (qty + FIFO-style valuation + earliest expiry).
CREATE TABLE mv_inventory_snapshot (
  business_id      CHAR(36)       NOT NULL,
  branch_id        CHAR(36)       NOT NULL,
  item_id          CHAR(36)       NOT NULL,
  qty_on_hand      DECIMAL(18, 4) NOT NULL,
  fifo_value       DECIMAL(18, 2) NOT NULL,
  earliest_expiry  DATE           NULL,
  refreshed_at     TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (business_id, branch_id, item_id)
);

CREATE INDEX idx_mvin_business ON mv_inventory_snapshot (business_id);
CREATE INDEX idx_mvin_branch ON mv_inventory_snapshot (business_id, branch_id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000103', 'reports.inventory.read',
   'View inventory valuation + expiry pipeline reports (MV-backed batches snapshot).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000103'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000103'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000103'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000103');
