-- Departments use the same markup percent categories already store.
-- 25 means the selling price is 125% of the buying price. Null means no default.

ALTER TABLE item_types
  ADD COLUMN default_markup_pct DECIMAL(9, 4) NULL AFTER is_default;
