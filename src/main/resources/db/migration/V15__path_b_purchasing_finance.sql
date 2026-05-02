-- Phase 2 Slice 2 — Path B raw purchase → breakdown → stock + AP + journal (PHASE_2_PLAN.md §Slice 2).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000047', 'purchasing.path_b.read',
   'View Path B (market trip) purchase sessions.'),
  ('11111111-0000-0000-0000-000000000048', 'purchasing.path_b.write',
   'Create Path B sessions, lines, and post breakdown (stock + invoice + journal).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000047'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000048'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000047'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000048'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000047'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000048');

ALTER TABLE items
  ADD COLUMN current_stock DECIMAL(14,4) NOT NULL DEFAULT 0 AFTER is_stocked;

CREATE TABLE ledger_accounts (
  id            CHAR(36) PRIMARY KEY,
  business_id   CHAR(36) NOT NULL,
  code          VARCHAR(16) NOT NULL,
  name          VARCHAR(255) NOT NULL,
  account_type  VARCHAR(16) NOT NULL,
  parent_id     CHAR(36) NULL,
  version       BIGINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_ledger_accounts_business_code (business_id, code),
  CONSTRAINT fk_ledger_accounts_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_ledger_accounts_business ON ledger_accounts (business_id);

CREATE TABLE journal_entries (
  id            CHAR(36) PRIMARY KEY,
  business_id   CHAR(36) NOT NULL,
  entry_date    DATE NOT NULL,
  source_type   VARCHAR(64) NOT NULL,
  source_id     CHAR(36) NOT NULL,
  memo          VARCHAR(500) NULL,
  version       BIGINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_journal_entries_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_journal_entries_business_source ON journal_entries (business_id, source_type, source_id);

CREATE TABLE journal_lines (
  id                 CHAR(36) PRIMARY KEY,
  journal_entry_id   CHAR(36) NOT NULL,
  ledger_account_id  CHAR(36) NOT NULL,
  debit              DECIMAL(14,2) NOT NULL DEFAULT 0,
  credit             DECIMAL(14,2) NOT NULL DEFAULT 0,
  CONSTRAINT fk_journal_lines_entry FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(id) ON DELETE CASCADE,
  CONSTRAINT fk_journal_lines_account FOREIGN KEY (ledger_account_id) REFERENCES ledger_accounts(id)
);

CREATE INDEX idx_journal_lines_entry ON journal_lines (journal_entry_id);

CREATE TABLE raw_purchase_sessions (
  id            CHAR(36) PRIMARY KEY,
  business_id   CHAR(36) NOT NULL,
  supplier_id   CHAR(36) NOT NULL,
  branch_id     CHAR(36) NOT NULL,
  received_at   TIMESTAMP NOT NULL,
  notes         TEXT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'draft',
  version       BIGINT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_rps_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_rps_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_rps_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE INDEX idx_rps_business_status ON raw_purchase_sessions (business_id, status);

CREATE TABLE raw_purchase_lines (
  id                 CHAR(36) PRIMARY KEY,
  session_id         CHAR(36) NOT NULL,
  sort_order         INT NOT NULL DEFAULT 0,
  description_text   VARCHAR(2000) NOT NULL,
  amount_money       DECIMAL(14,2) NOT NULL,
  suggested_item_id  CHAR(36) NULL,
  line_status        VARCHAR(16) NOT NULL DEFAULT 'pending',
  posted_item_id     CHAR(36) NULL,
  usable_qty         DECIMAL(14,4) NULL,
  wastage_qty        DECIMAL(14,4) NULL,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_rpl_session FOREIGN KEY (session_id) REFERENCES raw_purchase_sessions(id) ON DELETE CASCADE,
  CONSTRAINT fk_rpl_suggested_item FOREIGN KEY (suggested_item_id) REFERENCES items(id),
  CONSTRAINT fk_rpl_posted_item FOREIGN KEY (posted_item_id) REFERENCES items(id)
);

CREATE INDEX idx_rpl_session ON raw_purchase_lines (session_id);

CREATE TABLE inventory_batches (
  id                   CHAR(36) PRIMARY KEY,
  business_id          CHAR(36) NOT NULL,
  branch_id            CHAR(36) NOT NULL,
  item_id              CHAR(36) NOT NULL,
  supplier_id          CHAR(36) NOT NULL,
  batch_number         VARCHAR(64) NOT NULL,
  source_type          VARCHAR(32) NOT NULL,
  source_id            CHAR(36) NOT NULL,
  initial_quantity     DECIMAL(14,4) NOT NULL,
  quantity_remaining   DECIMAL(14,4) NOT NULL,
  unit_cost            DECIMAL(14,4) NOT NULL,
  received_at          TIMESTAMP NOT NULL,
  expiry_date          DATE NULL,
  status               VARCHAR(16) NOT NULL DEFAULT 'active',
  version              BIGINT NOT NULL DEFAULT 0,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ib_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_ib_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
  CONSTRAINT fk_ib_item FOREIGN KEY (item_id) REFERENCES items(id),
  CONSTRAINT fk_ib_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE INDEX idx_ib_item_branch ON inventory_batches (business_id, item_id, branch_id, status);

ALTER TABLE raw_purchase_lines
  ADD COLUMN inventory_batch_id CHAR(36) NULL,
  ADD CONSTRAINT fk_rpl_inventory_batch FOREIGN KEY (inventory_batch_id) REFERENCES inventory_batches(id);

CREATE TABLE stock_movements (
  id                CHAR(36) PRIMARY KEY,
  business_id       CHAR(36) NOT NULL,
  branch_id         CHAR(36) NOT NULL,
  item_id           CHAR(36) NOT NULL,
  batch_id          CHAR(36) NULL,
  movement_type     VARCHAR(24) NOT NULL,
  reference_type    VARCHAR(64) NOT NULL,
  reference_id      CHAR(36) NOT NULL,
  quantity_delta    DECIMAL(14,4) NOT NULL,
  unit_cost         DECIMAL(14,4) NULL,
  reason            VARCHAR(255) NULL,
  notes             VARCHAR(2000) NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by        CHAR(36) NULL,
  CONSTRAINT fk_sm_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_sm_branch FOREIGN KEY (branch_id) REFERENCES branches(id),
  CONSTRAINT fk_sm_item FOREIGN KEY (item_id) REFERENCES items(id),
  CONSTRAINT fk_sm_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches(id)
);

CREATE INDEX idx_sm_item_created ON stock_movements (business_id, item_id, created_at);

CREATE TABLE supplier_invoices (
  id                        CHAR(36) PRIMARY KEY,
  business_id               CHAR(36) NOT NULL,
  supplier_id               CHAR(36) NOT NULL,
  raw_purchase_session_id   CHAR(36) NULL,
  invoice_number            VARCHAR(64) NOT NULL,
  invoice_date              DATE NOT NULL,
  due_date                  DATE NULL,
  subtotal                  DECIMAL(14,2) NOT NULL,
  tax_total                 DECIMAL(14,2) NOT NULL DEFAULT 0,
  grand_total               DECIMAL(14,2) NOT NULL,
  status                    VARCHAR(16) NOT NULL,
  notes                     TEXT NULL,
  version                   BIGINT NOT NULL DEFAULT 0,
  created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_supplier_invoices_business_no (business_id, invoice_number),
  CONSTRAINT fk_si_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_si_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_si_rps FOREIGN KEY (raw_purchase_session_id) REFERENCES raw_purchase_sessions(id)
);

CREATE INDEX idx_supplier_invoices_supplier ON supplier_invoices (business_id, supplier_id, status);

CREATE TABLE supplier_invoice_lines (
  id               CHAR(36) PRIMARY KEY,
  invoice_id       CHAR(36) NOT NULL,
  description      VARCHAR(2000) NOT NULL,
  item_id          CHAR(36) NULL,
  qty              DECIMAL(14,4) NOT NULL,
  unit_cost        DECIMAL(14,4) NOT NULL,
  line_total       DECIMAL(14,2) NOT NULL,
  sort_order       INT NOT NULL DEFAULT 0,
  raw_line_id      CHAR(36) NULL,
  CONSTRAINT fk_sil_invoice FOREIGN KEY (invoice_id) REFERENCES supplier_invoices(id) ON DELETE CASCADE,
  CONSTRAINT fk_sil_item FOREIGN KEY (item_id) REFERENCES items(id),
  CONSTRAINT fk_sil_raw_line FOREIGN KEY (raw_line_id) REFERENCES raw_purchase_lines(id)
);

CREATE INDEX idx_sil_invoice ON supplier_invoice_lines (invoice_id);

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '1200', 'Inventory', 'asset', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '2100', 'Accounts Payable – Suppliers', 'liability', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;

INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '5210', 'Inventory shrinkage (wastage)', 'expense', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL;
