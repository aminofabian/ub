-- ADR 0042: what a buyer owes a supplier's shop for a dispatched trade order, and what was paid.
-- One invoice per sales order. Receipts are money received against an invoice.

CREATE TABLE sales_invoices (
  id                   VARCHAR(36)   NOT NULL,
  business_id          VARCHAR(36)   NOT NULL,
  sales_order_id       VARCHAR(36)   NOT NULL,
  invoice_no           VARCHAR(32)   NOT NULL,
  status               VARCHAR(16)   NOT NULL COMMENT 'open, part_paid, paid',
  currency             VARCHAR(8)    NOT NULL,
  total                DECIMAL(14,2) NOT NULL,
  amount_paid          DECIMAL(14,2) NOT NULL DEFAULT 0,
  cost_of_goods        DECIMAL(14,2) NOT NULL DEFAULT 0,
  issued_on            DATE          NOT NULL,
  due_on               DATE          NOT NULL,
  buyer_name           VARCHAR(255)  NOT NULL,
  buyer_business_name  VARCHAR(255)  NULL,
  buyer_phone          VARCHAR(64)   NOT NULL,
  version              BIGINT        NOT NULL DEFAULT 0,
  created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_sales_invoices_order (sales_order_id),
  KEY idx_sales_invoices_open (business_id, status, due_on)
);

CREATE TABLE sales_receipts (
  id           VARCHAR(36)   NOT NULL,
  business_id  VARCHAR(36)   NOT NULL,
  invoice_id   VARCHAR(36)   NOT NULL,
  amount       DECIMAL(14,2) NOT NULL,
  method       VARCHAR(16)   NOT NULL COMMENT 'cash, mpesa, bank',
  reference    VARCHAR(128)  NULL,
  received_on  DATE          NOT NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sales_receipts_invoice (invoice_id),
  CONSTRAINT fk_sales_receipts_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id)
);
