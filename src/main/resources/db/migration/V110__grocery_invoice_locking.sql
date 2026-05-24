-- V110: Grocery invoice locking — prevents duplicate cashier processing.

ALTER TABLE grocery_invoices
  ADD COLUMN locked_by CHAR(36) NULL AFTER sale_id,
  ADD COLUMN locked_at TIMESTAMP NULL AFTER locked_by,
  ADD COLUMN lock_expires_at TIMESTAMP NULL AFTER locked_at;

ALTER TABLE grocery_invoices
  ADD CONSTRAINT fk_gi_locked_by FOREIGN KEY (locked_by) REFERENCES users(id);

ALTER TABLE grocery_invoices
  ADD KEY idx_gi_locked (locked_by, lock_expires_at);
