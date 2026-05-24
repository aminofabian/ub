-- V109: Grocery Checkout — two-phase invoice-to-payment tables and permissions.

CREATE TABLE grocery_invoices (
    id                CHAR(36)      NOT NULL PRIMARY KEY,
    business_id       CHAR(36)      NOT NULL,
    branch_id         CHAR(36)      NOT NULL,
    status            VARCHAR(24)   NOT NULL DEFAULT 'pending_payment',
    barcode_code      VARCHAR(191)  NOT NULL,
    subtotal          DECIMAL(14,2) NOT NULL,
    grand_total       DECIMAL(14,2) NOT NULL,
    created_by        CHAR(36)      NOT NULL,
    cancelled_by      CHAR(36)      NULL,
    cancelled_at      TIMESTAMP     NULL,
    cancelled_reason  VARCHAR(500)  NULL,
    paid_by           CHAR(36)      NULL,
    paid_at           TIMESTAMP     NULL,
    sale_id           CHAR(36)      NULL,
    expires_at        TIMESTAMP     NOT NULL,
    notes             VARCHAR(2000) NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_grocery_invoices_barcode (barcode_code),
    CONSTRAINT fk_gi_business     FOREIGN KEY (business_id)   REFERENCES businesses(id),
    CONSTRAINT fk_gi_branch       FOREIGN KEY (branch_id)     REFERENCES branches(id),
    CONSTRAINT fk_gi_created_by   FOREIGN KEY (created_by)    REFERENCES users(id),
    CONSTRAINT fk_gi_cancelled_by FOREIGN KEY (cancelled_by)  REFERENCES users(id),
    CONSTRAINT fk_gi_paid_by      FOREIGN KEY (paid_by)       REFERENCES users(id),
    CONSTRAINT fk_gi_sale         FOREIGN KEY (sale_id)       REFERENCES sales(id),

    KEY idx_gi_business_status    (business_id, status),
    KEY idx_gi_business_barcode   (business_id, barcode_code),
    KEY idx_gi_expires            (status, expires_at)
);

CREATE TABLE grocery_invoice_lines (
    id           CHAR(36)       NOT NULL PRIMARY KEY,
    invoice_id   CHAR(36)       NOT NULL,
    item_id      CHAR(36)       NOT NULL,
    item_name    VARCHAR(500)   NOT NULL,
    line_index   INT            NOT NULL,
    quantity     DECIMAL(14,4)  NOT NULL,
    unit_name    VARCHAR(16)    NOT NULL DEFAULT 'each',
    unit_price   DECIMAL(14,4)  NOT NULL,
    line_total   DECIMAL(14,2)  NOT NULL,

    CONSTRAINT fk_gil_invoice FOREIGN KEY (invoice_id) REFERENCES grocery_invoices(id) ON DELETE CASCADE,
    CONSTRAINT fk_gil_item    FOREIGN KEY (item_id)    REFERENCES items(id),

    KEY idx_gil_invoice (invoice_id)
);

-- Permissions for grocery checkout workflow

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000110', 'grocery.invoices.create',
   'Create pending grocery invoices.'),
  ('11111111-0000-0000-0000-000000000111', 'grocery.invoices.read',
   'View grocery invoices.'),
  ('11111111-0000-0000-0000-000000000112', 'grocery.invoices.cancel',
   'Cancel pending grocery invoices.'),
  ('11111111-0000-0000-0000-000000000113', 'grocery.invoices.pay',
   'Pay grocery invoices (creates sale, deducts stock).');

-- Grant to: owner, manager, cashier, stock_manager
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name IN ('owner', 'manager', 'cashier', 'stock_manager')
  AND p.permission_key IN (
      'grocery.invoices.create',
      'grocery.invoices.read',
      'grocery.invoices.cancel',
      'grocery.invoices.pay'
  );
