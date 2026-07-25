-- Global supplier hub: unique vanity username on marketplace_suppliers.

ALTER TABLE marketplace_suppliers
  ADD COLUMN username VARCHAR(64) NULL AFTER name;

CREATE UNIQUE INDEX uq_marketplace_suppliers_username
  ON marketplace_suppliers (username);
