-- Phase 2 Slice 3 — Path A: PO → GRN → supplier invoice + 3-way match (PHASE_2_PLAN.md §Slice 3).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000049', 'purchasing.path_a.read',
   'View Path A purchase orders and goods receipts.'),
  ('11111111-0000-0000-0000-000000000050', 'purchasing.path_a.write',
   'Create Path A POs, post GRNs, and post supplier invoices linked to GRNs.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000049'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000050'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000049'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000050'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000049'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000050');

CREATE TABLE purchase_orders (
  id            CHAR(36) PRIMARY KEY,
  business_id   CHAR(36) NOT NULL,
  supplier_id   CHAR(36) NOT NULL,
  branch_id     CHAR(36) NOT NULL,
  po_number     VARCHAR(64) NOT NULL,
  expected_date DATE NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'draft',
  notes         TEXT NULL,
  version       BIGINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_purchase_orders_business_po (business_id, po_number),
  CONSTRAINT fk_po_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_po_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_po_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE INDEX idx_purchase_orders_business_status ON purchase_orders (business_id, status);

CREATE TABLE purchase_order_lines (
  id                   CHAR(36) PRIMARY KEY,
  purchase_order_id    CHAR(36) NOT NULL,
  sort_order           INT NOT NULL DEFAULT 0,
  item_id              CHAR(36) NOT NULL,
  qty_ordered          DECIMAL(14,4) NOT NULL,
  qty_received         DECIMAL(14,4) NOT NULL DEFAULT 0,
  unit_estimated_cost  DECIMAL(14,4) NOT NULL,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pol_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_pol_item FOREIGN KEY (item_id) REFERENCES items(id)
);

CREATE INDEX idx_pol_po ON purchase_order_lines (purchase_order_id);

CREATE TABLE goods_receipts (
  id                 CHAR(36) PRIMARY KEY,
  business_id        CHAR(36) NOT NULL,
  purchase_order_id  CHAR(36) NOT NULL,
  branch_id          CHAR(36) NOT NULL,
  received_at        TIMESTAMP NOT NULL,
  status             VARCHAR(16) NOT NULL DEFAULT 'draft',
  grni_amount        DECIMAL(14,2) NULL,
  notes              TEXT NULL,
  version            BIGINT NOT NULL DEFAULT 0,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_grn_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_grn_po FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_grn_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE INDEX idx_goods_receipts_po ON goods_receipts (purchase_order_id, status);

CREATE TABLE goods_receipt_lines (
  id                     CHAR(36) PRIMARY KEY,
  goods_receipt_id       CHAR(36) NOT NULL,
  purchase_order_line_id CHAR(36) NOT NULL,
  qty_received           DECIMAL(14,4) NOT NULL,
  inventory_batch_id     CHAR(36) NULL,
  created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_grnl_grn FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts(id) ON DELETE CASCADE,
  CONSTRAINT fk_grnl_pol FOREIGN KEY (purchase_order_line_id) REFERENCES purchase_order_lines(id),
  CONSTRAINT fk_grnl_batch FOREIGN KEY (inventory_batch_id) REFERENCES inventory_batches(id)
);

CREATE INDEX idx_grnl_grn ON goods_receipt_lines (goods_receipt_id);

ALTER TABLE supplier_invoices
  ADD COLUMN goods_receipt_id CHAR(36) NULL,
  ADD CONSTRAINT fk_si_grn FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts(id);

CREATE INDEX idx_supplier_invoices_grn ON supplier_invoices (goods_receipt_id);

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '2150', 'Goods received not invoiced (GRNI)', 'liability', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '5220', 'Purchase price variance', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;
