-- Remote / delivery grocery invoices: customer phone + STK tracking.
-- Admin toggle: auto-settle remote invoices when M-Pesa STK succeeds (default ON).

ALTER TABLE grocery_invoices
  ADD COLUMN customer_phone VARCHAR(32) NULL,
  ADD COLUMN customer_id CHAR(36) NULL,
  ADD COLUMN is_remote TINYINT(1) NOT NULL DEFAULT 0,
  ADD COLUMN last_stk_status VARCHAR(24) NULL,
  ADD COLUMN last_stk_at TIMESTAMP(3) NULL;

CREATE INDEX idx_grocery_invoices_remote_pending
  ON grocery_invoices (business_id, branch_id, is_remote, status);

ALTER TABLE business_credit_settings
  ADD COLUMN remote_invoice_stk_auto_settle TINYINT(1) NOT NULL DEFAULT 1;
