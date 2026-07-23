-- Parent link for global product variants (mirrors items.variant_of_item_id).
-- Promote remaps tenant parent → global id; adopt recreates the link via createVariant.

ALTER TABLE global_products
  ADD COLUMN variant_of_global_product_id CHAR(36) NULL
    AFTER is_package_variant;

CREATE INDEX idx_global_products_variant_of
  ON global_products (variant_of_global_product_id);

-- Soft FK: parent must live in the same catalog. Enforced in app (promote/adopt/SA write)
-- rather than a DB FK so archive/replace can clear children without cascade surprises.
