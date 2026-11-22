-- ADR 0038, P1: a supplier's passport can be bound to the supplier's own shop (a business).
-- NULL = a passport with no shop yet, which keeps working exactly as before.

ALTER TABLE marketplace_suppliers
  ADD COLUMN business_id VARCHAR(36) NULL;

-- One passport per shop. A unique index allows any number of NULLs on MySQL and MariaDB.
CREATE UNIQUE INDEX uk_marketplace_supplier_business ON marketplace_suppliers (business_id);
