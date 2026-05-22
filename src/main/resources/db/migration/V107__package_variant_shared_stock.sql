-- Package / selling-unit variants must not hold independent stock batches.
UPDATE items
SET is_stocked = false
WHERE deleted_at IS NULL
  AND variant_of_item_id IS NOT NULL
  AND packaging_unit_qty IS NOT NULL
  AND packaging_unit_qty > 1
  AND is_stocked = true;

UPDATE items
SET is_package_variant = true
WHERE deleted_at IS NULL
  AND variant_of_item_id IS NOT NULL
  AND packaging_unit_qty IS NOT NULL
  AND packaging_unit_qty > 1
  AND is_package_variant = false;
