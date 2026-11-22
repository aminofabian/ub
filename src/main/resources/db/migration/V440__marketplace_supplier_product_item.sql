-- ADR 0038, P1: a passport product points at the product in the supplier's own shop once it has
-- been copied there. NULL = not in a shop (the supplier has no shop, or has not copied it yet).

ALTER TABLE marketplace_supplier_products
  ADD COLUMN item_id VARCHAR(36) NULL;

CREATE INDEX idx_msp_supplier_item ON marketplace_supplier_products (marketplace_supplier_id, item_id);
