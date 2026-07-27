-- Heal phone-derived passport names ("Supplier 2874") from linked local shop names.
UPDATE marketplace_suppliers ms
INNER JOIN (
    SELECT
        s.marketplace_supplier_id AS id,
        MIN(s.name) AS better_name
    FROM suppliers s
    WHERE s.deleted_at IS NULL
      AND s.marketplace_supplier_id IS NOT NULL
      AND s.name IS NOT NULL
      AND TRIM(s.name) <> ''
      AND LOWER(s.name) NOT REGEXP '^supplier[[:space:]]+[0-9]{2,8}$'
    GROUP BY s.marketplace_supplier_id
) pick ON pick.id = ms.id
SET
    ms.name = pick.better_name,
    ms.updated_at = CURRENT_TIMESTAMP(3)
WHERE ms.name IS NULL
   OR TRIM(ms.name) = ''
   OR LOWER(ms.name) REGEXP '^supplier[[:space:]]+[0-9]{2,8}$';

-- Keep marketplace identity index names in sync for search.
UPDATE supplier_identity_index idx
INNER JOIN marketplace_suppliers ms
    ON ms.id = idx.marketplace_supplier_id
   AND idx.supplier_id IS NULL
   AND idx.source = 'marketplace'
SET idx.name_normalized = LOWER(TRIM(ms.name))
WHERE idx.name_normalized <> LOWER(TRIM(ms.name));
