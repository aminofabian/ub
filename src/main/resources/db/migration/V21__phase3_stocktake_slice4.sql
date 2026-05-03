-- Phase 3 Slice 4 — stock-take sessions & adjustment approvals (PHASE_3_PLAN.md).

CREATE TABLE stock_take_sessions (
  id          CHAR(36)     PRIMARY KEY,
  business_id CHAR(36)     NOT NULL,
  branch_id   CHAR(36)     NOT NULL,
  status      VARCHAR(24)  NOT NULL,
  notes       VARCHAR(2000) NULL,
  version     BIGINT       NOT NULL DEFAULT 0,
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  started_by  CHAR(36)     NULL,
  closed_at   TIMESTAMP    NULL,
  CONSTRAINT fk_stake_sessions_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_stake_sessions_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

CREATE INDEX idx_stake_sessions_business ON stock_take_sessions (business_id);

CREATE TABLE stock_take_lines (
  id                   CHAR(36)       PRIMARY KEY,
  session_id           CHAR(36)       NOT NULL,
  item_id              CHAR(36)       NOT NULL,
  system_qty_snapshot  DECIMAL(14, 4) NOT NULL,
  counted_qty          DECIMAL(14, 4) NULL,
  note                 VARCHAR(500)   NULL,
  sort_order           INT            NOT NULL,
  CONSTRAINT fk_stake_lines_session FOREIGN KEY (session_id) REFERENCES stock_take_sessions (id) ON DELETE CASCADE,
  CONSTRAINT fk_stake_lines_item FOREIGN KEY (item_id) REFERENCES items (id),
  CONSTRAINT uq_stake_line_session_item UNIQUE (session_id, item_id)
);

CREATE INDEX idx_stake_lines_session ON stock_take_lines (session_id);

CREATE TABLE stock_adjustment_requests (
  id                   CHAR(36)       PRIMARY KEY,
  business_id          CHAR(36)       NOT NULL,
  branch_id            CHAR(36)       NOT NULL,
  stock_take_line_id   CHAR(36)       NOT NULL,
  item_id              CHAR(36)       NOT NULL,
  adjustment_type      VARCHAR(32)    NOT NULL,
  variance_qty         DECIMAL(14, 4) NOT NULL,
  system_qty_snapshot  DECIMAL(14, 4) NOT NULL,
  counted_qty          DECIMAL(14, 4) NOT NULL,
  reason               VARCHAR(64)    NOT NULL,
  notes                VARCHAR(2000)  NULL,
  status               VARCHAR(16)    NOT NULL,
  requested_by         CHAR(36)       NULL,
  decided_by           CHAR(36)       NULL,
  decided_at           TIMESTAMP      NULL,
  created_at           TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_sar_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_sar_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_sar_line FOREIGN KEY (stock_take_line_id) REFERENCES stock_take_lines (id) ON DELETE CASCADE,
  CONSTRAINT fk_sar_item FOREIGN KEY (item_id) REFERENCES items (id)
);

CREATE INDEX idx_sar_business_status ON stock_adjustment_requests (business_id, status);
CREATE INDEX idx_sar_line ON stock_adjustment_requests (stock_take_line_id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000057', 'stocktake.read',
   'View stock-take sessions and adjustment requests.'),
  ('11111111-0000-0000-0000-000000000058', 'stocktake.run',
   'Start sessions, enter counts, and close stock-takes.'),
  ('11111111-0000-0000-0000-000000000059', 'stocktake.approve',
   'Approve or reject stock-take variance adjustment requests.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000057'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000058'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000059'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000057'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000058'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000059'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000057'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000058');
