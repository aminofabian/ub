-- Phase 4 Slice 4 — refunds (PHASE_4_PLAN.md, implement.md §8.2 §14.5).

ALTER TABLE sales
  ADD COLUMN refunded_total DECIMAL(14, 2) NOT NULL DEFAULT 0;

CREATE TABLE refunds (
  id                 CHAR(36)      PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  sale_id            CHAR(36)      NOT NULL,
  idempotency_key    VARCHAR(191)  NOT NULL,
  journal_entry_id   CHAR(36)      NULL,
  refunded_by        CHAR(36)      NOT NULL,
  refunded_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  total_refunded     DECIMAL(14, 2) NOT NULL,
  reason             VARCHAR(512)  NULL,
  status             VARCHAR(16)   NOT NULL DEFAULT 'completed',
  UNIQUE KEY uq_refunds_business_idem (business_id, idempotency_key),
  CONSTRAINT fk_refunds_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_refunds_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
  CONSTRAINT fk_refunds_journal FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
  CONSTRAINT fk_refunds_user FOREIGN KEY (refunded_by) REFERENCES users (id)
);

CREATE INDEX idx_refunds_sale ON refunds (sale_id);

CREATE TABLE refund_lines (
  id            CHAR(36)       PRIMARY KEY,
  refund_id     CHAR(36)       NOT NULL,
  sale_item_id  CHAR(36)       NOT NULL,
  quantity      DECIMAL(14, 4) NOT NULL,
  amount        DECIMAL(14, 2) NOT NULL,
  CONSTRAINT fk_rl_refund FOREIGN KEY (refund_id) REFERENCES refunds (id),
  CONSTRAINT fk_rl_sale_item FOREIGN KEY (sale_item_id) REFERENCES sale_items (id)
);

CREATE INDEX idx_refund_lines_refund ON refund_lines (refund_id);

CREATE TABLE refund_payments (
  id          CHAR(36)      PRIMARY KEY,
  refund_id   CHAR(36)      NOT NULL,
  method      VARCHAR(24)   NOT NULL,
  amount      DECIMAL(14, 2) NOT NULL,
  reference   VARCHAR(128)  NULL,
  sort_order  INT           NOT NULL DEFAULT 0,
  CONSTRAINT fk_rp_refund FOREIGN KEY (refund_id) REFERENCES refunds (id)
);

CREATE INDEX idx_refund_payments_refund ON refund_payments (refund_id, sort_order);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000070', 'sales.refund.create',
   'Create refunds that restore stock and post reversal journals.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000070'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000070'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000070'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000070');
