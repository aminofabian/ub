-- Flat packaging / variant metadata on global products (no parent/child groups yet).
-- Adopt maps packaging fields onto standalone tenant SKUs; is_package_variant is
-- informational on the template — adopted items are not linked as live package variants.

ALTER TABLE global_products
  ADD COLUMN variant_name VARCHAR(255) NULL AFTER size,
  ADD COLUMN is_package_variant BOOLEAN NOT NULL DEFAULT FALSE AFTER is_stocked,
  ADD COLUMN packaging_unit_name VARCHAR(255) NULL AFTER is_package_variant,
  ADD COLUMN packaging_unit_qty DECIMAL(14,4) NULL AFTER packaging_unit_name;
