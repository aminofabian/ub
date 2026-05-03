-- Phase 3 Slice 3 — branch-to-branch stock transfers (PHASE_3_PLAN.md).

CREATE TABLE stock_transfers (
  id             CHAR(36)     PRIMARY KEY,
  business_id    CHAR(36)     NOT NULL,
  from_branch_id CHAR(36)     NOT NULL,
  to_branch_id   CHAR(36)     NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  notes          VARCHAR(2000) NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  created_by     CHAR(36)     NULL,
  CONSTRAINT fk_stock_transfers_business
    FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_stock_transfers_from_branch
    FOREIGN KEY (from_branch_id) REFERENCES branches (id),
  CONSTRAINT fk_stock_transfers_to_branch
    FOREIGN KEY (to_branch_id) REFERENCES branches (id),
  CONSTRAINT ck_stock_transfers_branches CHECK (from_branch_id <> to_branch_id)
);

CREATE INDEX idx_stock_transfers_business ON stock_transfers (business_id);

CREATE TABLE stock_transfer_lines (
  id          CHAR(36)    PRIMARY KEY,
  transfer_id CHAR(36)    NOT NULL,
  item_id     CHAR(36)    NOT NULL,
  quantity    DECIMAL(14, 4) NOT NULL,
  sort_order  INT         NOT NULL,
  CONSTRAINT fk_stock_transfer_lines_transfer
    FOREIGN KEY (transfer_id) REFERENCES stock_transfers (id) ON DELETE CASCADE,
  CONSTRAINT fk_stock_transfer_lines_item
    FOREIGN KEY (item_id) REFERENCES items (id)
);

CREATE INDEX idx_stock_transfer_lines_transfer ON stock_transfer_lines (transfer_id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000056', 'inventory.transfer',
   'Create and complete inter-branch stock transfers.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000056'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000056'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000056');
