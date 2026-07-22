-- Phase 5 — empty published Uganda retail catalog for region resolution.
-- No categories/products/packs: SA curates into it; UG country_code resolves here.

INSERT INTO global_catalogs (id, code, name, region_code, currency, status, version)
VALUES (
  '00000000-0000-0000-0000-000000000002',
  'ug-retail',
  'Uganda Retail Catalog',
  'UG',
  'UGX',
  'published',
  1
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  region_code = VALUES(region_code),
  currency = VALUES(currency),
  status = VALUES(status);
