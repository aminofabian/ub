-- Empty published starter packs for new onboarding verticals (ops fills products via SA).
-- Linked to store_kit_id so catalog “for you” ranking works immediately.

SET @catalog_id = (
  SELECT id FROM global_catalogs WHERE code = 'default' LIMIT 1
);

INSERT INTO global_product_packs (
  id, catalog_id, code, name, description, store_kit_id, status, sort_order
)
SELECT
  '00000000-0000-0000-0000-000000010010',
  @catalog_id,
  'cosmetics-starter',
  'Cosmetics Starter',
  'Starter pack for cosmetics and beauty shops. Add products in Super Admin.',
  'cosmetics',
  'published',
  10
WHERE @catalog_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM global_product_packs
    WHERE catalog_id = @catalog_id AND code = 'cosmetics-starter'
  );

INSERT INTO global_product_packs (
  id, catalog_id, code, name, description, store_kit_id, status, sort_order
)
SELECT
  '00000000-0000-0000-0000-000000010011',
  @catalog_id,
  'wines-spirits-starter',
  'Wines & Spirits Starter',
  'Starter pack for wines and spirits shops. Add products in Super Admin.',
  'wines-spirits',
  'published',
  11
WHERE @catalog_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM global_product_packs
    WHERE catalog_id = @catalog_id AND code = 'wines-spirits-starter'
  );
