-- Page seals for shop-local public supplier portals (/s/{slug} on tenant hosts).
ALTER TABLE suppliers
  ADD COLUMN page_sealed TINYINT(1) NOT NULL DEFAULT 0 AFTER marketplace_supplier_id,
  ADD COLUMN page_pin_hash VARCHAR(255) NULL AFTER page_sealed,
  ADD COLUMN page_seal_verified_at TIMESTAMP(6) NULL AFTER page_pin_hash,
  ADD COLUMN page_seal_updated_at TIMESTAMP(6) NULL AFTER page_seal_verified_at;
