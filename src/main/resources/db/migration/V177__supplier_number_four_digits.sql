-- Four-digit global supplier numbers (S-0001 …). Reformat any existing six-digit values.

UPDATE marketplace_suppliers
SET supplier_number = CONCAT(
  'S-',
  LPAD(CAST(SUBSTRING(supplier_number, 3) AS UNSIGNED), 4, '0')
)
WHERE supplier_number REGEXP '^S-[0-9]+$';

UPDATE supplier_identity_index sii
JOIN marketplace_suppliers ms ON ms.id = sii.marketplace_supplier_id
SET sii.supplier_number_normalized = ms.supplier_number
WHERE ms.supplier_number IS NOT NULL;

-- Keep sequence in sync with highest assigned number.
UPDATE platform_supplier_number_seq
SET next_value = (
      SELECT COALESCE(MAX(CAST(SUBSTRING(supplier_number, 3) AS UNSIGNED)), 0) + 1
      FROM marketplace_suppliers
      WHERE supplier_number IS NOT NULL
    ),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = '00000000-0000-0000-0000-000000000001';
