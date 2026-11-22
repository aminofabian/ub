-- P2-5: what the buying shop says arrived, per line. NULL = not said yet.
ALTER TABLE trade_order_lines
  ADD COLUMN received_qty DECIMAL(14,4) NULL;

-- P2-6: a credit note takes money off what is owed on an invoice (a shortage, a return, a price fix).
ALTER TABLE sales_invoices
  ADD COLUMN amount_credited DECIMAL(14,2) NOT NULL DEFAULT 0;

CREATE TABLE sales_credit_notes (
  id           VARCHAR(36)   NOT NULL,
  business_id  VARCHAR(36)   NOT NULL,
  invoice_id   VARCHAR(36)   NOT NULL,
  amount       DECIMAL(14,2) NOT NULL,
  reason       VARCHAR(500)  NOT NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sales_credit_notes_invoice (invoice_id),
  CONSTRAINT fk_sales_credit_notes_invoice FOREIGN KEY (invoice_id) REFERENCES sales_invoices (id)
);
