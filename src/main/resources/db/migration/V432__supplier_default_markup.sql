-- Suppliers use the same markup percent categories and departments store.
-- When a product names this supplier, this markup wins. Null means no override.

ALTER TABLE suppliers
  ADD COLUMN default_markup_pct DECIMAL(9, 4) NULL AFTER credit_limit;
