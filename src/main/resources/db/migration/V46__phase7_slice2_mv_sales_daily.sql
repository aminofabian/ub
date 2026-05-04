-- Phase 7 Slice 2 — mv_sales_daily summary table (PHASE_7_PLAN.md §Slice 2).
-- MySQL adaptation of implement.md §9.6 mv_sales_daily(business_id, branch_id, day, item_id,
-- qty, revenue, cost, profit). Refresh strategy = full delete-then-insert per tenant
-- (PHASE_7_PLAN.md Locked ADRs / Slice 2 deliverables).

-- Note: column is `business_day`, not `day`. `DAY` is a reserved keyword in H2 / SQL:1999;
-- using a non-reserved name keeps Flyway and the H2-MySQL test profile in lockstep.
CREATE TABLE mv_sales_daily (
  business_id  CHAR(36)       NOT NULL,
  branch_id    CHAR(36)       NOT NULL,
  business_day DATE           NOT NULL,
  item_id      CHAR(36)       NOT NULL,
  qty          DECIMAL(18, 4) NOT NULL,
  revenue      DECIMAL(18, 2) NOT NULL,
  cost         DECIMAL(18, 2) NOT NULL,
  profit       DECIMAL(18, 2) NOT NULL,
  refreshed_at TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (business_id, branch_id, business_day, item_id)
);

CREATE INDEX idx_mvsd_business_day ON mv_sales_daily (business_id, business_day);
CREATE INDEX idx_mvsd_business_branch_day ON mv_sales_daily (business_id, branch_id, business_day);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000101', 'reports.sales.read',
   'View sales reports (sales register / profit by item, MV-backed and today OLTP).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000101'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000101'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000101'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000101');
