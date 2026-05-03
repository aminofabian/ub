-- Phase 4 Slice 2 — idempotent cash/M-Pesa-manual sale + GL + drawer (PHASE_4_PLAN.md §Slice 2).

CREATE TABLE sales (
  id                 CHAR(36)      PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  branch_id          CHAR(36)      NOT NULL,
  shift_id           CHAR(36)      NOT NULL,
  status             VARCHAR(16)   NOT NULL,
  idempotency_key    VARCHAR(191)  NOT NULL,
  grand_total        DECIMAL(14, 2) NOT NULL,
  journal_entry_id   CHAR(36)      NULL,
  sold_by            CHAR(36)      NOT NULL,
  sold_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version            BIGINT        NOT NULL DEFAULT 0,
  created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_sales_business_idem (business_id, idempotency_key),
  CONSTRAINT fk_sales_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_sales_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_sales_shift FOREIGN KEY (shift_id) REFERENCES shifts (id),
  CONSTRAINT fk_sales_sold_by FOREIGN KEY (sold_by) REFERENCES users (id),
  CONSTRAINT fk_sales_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id)
);

CREATE INDEX idx_sales_branch_sold ON sales (business_id, branch_id, sold_at);

CREATE TABLE sale_items (
  id          CHAR(36)       PRIMARY KEY,
  sale_id     CHAR(36)       NOT NULL,
  line_index  INT            NOT NULL,
  item_id     CHAR(36)       NOT NULL,
  batch_id    CHAR(36)       NOT NULL,
  quantity    DECIMAL(14, 4) NOT NULL,
  unit_price  DECIMAL(14, 4) NOT NULL,
  line_total  DECIMAL(14, 2) NOT NULL,
  unit_cost   DECIMAL(14, 4) NOT NULL,
  cost_total  DECIMAL(14, 2) NOT NULL,
  profit      DECIMAL(14, 2) NOT NULL,
  CONSTRAINT fk_si_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
  CONSTRAINT fk_si_item FOREIGN KEY (item_id) REFERENCES items (id),
  CONSTRAINT fk_si_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id)
);

CREATE INDEX idx_sale_items_sale ON sale_items (sale_id);

CREATE TABLE sale_payments (
  id         CHAR(36)      PRIMARY KEY,
  sale_id    CHAR(36)      NOT NULL,
  method     VARCHAR(24)   NOT NULL,
  amount     DECIMAL(14, 2) NOT NULL,
  reference  VARCHAR(128)  NULL,
  CONSTRAINT fk_sp_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
);

CREATE INDEX idx_sale_payments_sale ON sale_payments (sale_id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000067', 'sales.sell',
   'Complete POS sales (stock, GL, and drawer effects where applicable).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000067'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000067'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000067'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000067');
