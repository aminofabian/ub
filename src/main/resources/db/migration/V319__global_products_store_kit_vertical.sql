-- Vertical scoping for global catalog browse: a pharmacy must not see grocery SKUs and vice versa.
-- store_kit_id is the product's vertical (e.g. 'pharmacy') or the general 'grocery' bucket.
-- NULL means unrestricted (visible to every shop) — the safe default for future inserts.

ALTER TABLE global_products
  ADD COLUMN store_kit_id VARCHAR(64) NULL;

CREATE INDEX idx_global_products_catalog_store_kit
  ON global_products (catalog_id, store_kit_id);

-- Specialized verticals are derived from the published pack each product already belongs to.
UPDATE global_products gp
  JOIN global_product_pack_items pi ON pi.global_product_id = gp.id
  JOIN global_product_packs p ON p.id = pi.pack_id
   SET gp.store_kit_id = p.store_kit_id
 WHERE gp.store_kit_id IS NULL
   AND p.store_kit_id IN ('pharmacy', 'cosmetics', 'wines-spirits');

-- Everything else is general grocery retail.
UPDATE global_products
   SET store_kit_id = 'grocery'
 WHERE store_kit_id IS NULL;
