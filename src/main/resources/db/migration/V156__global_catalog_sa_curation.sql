-- Super Admin global catalog curation support:
--   * image_public_id for explicit clear/destroy of platform assets
--   * dedup_barcode unique among non-archived rows (archived / blank / ambiguous → NULL)
-- Uses plain column + triggers (MariaDB-safe; see V113).

ALTER TABLE global_products
  ADD COLUMN image_public_id VARCHAR(512) NULL AFTER image_url;

ALTER TABLE global_products
  ADD COLUMN dedup_barcode VARCHAR(191) NULL AFTER image_public_id;

-- Only stamp barcodes that are unique within the catalog so existing seed
-- duplicates do not block the unique index. Ops can reconcile via SA UI.
UPDATE global_products gp
  INNER JOIN (
    SELECT catalog_id, barcode
      FROM global_products
     WHERE status <> 'archived'
       AND barcode IS NOT NULL
       AND TRIM(barcode) <> ''
     GROUP BY catalog_id, barcode
    HAVING COUNT(*) = 1
  ) uniq
    ON uniq.catalog_id = gp.catalog_id
   AND uniq.barcode = gp.barcode
   SET gp.dedup_barcode = gp.barcode
 WHERE gp.status <> 'archived';

ALTER TABLE global_products
  ADD UNIQUE KEY uq_global_products_dedup_barcode (catalog_id, dedup_barcode);

DROP TRIGGER IF EXISTS trg_global_products_dedup_bi;
DROP TRIGGER IF EXISTS trg_global_products_dedup_bu;

CREATE TRIGGER trg_global_products_dedup_bi
BEFORE INSERT ON global_products
FOR EACH ROW
SET NEW.dedup_barcode = IF(
  NEW.status = 'archived' OR NEW.barcode IS NULL OR TRIM(NEW.barcode) = '',
  NULL,
  NEW.barcode
);

CREATE TRIGGER trg_global_products_dedup_bu
BEFORE UPDATE ON global_products
FOR EACH ROW
SET NEW.dedup_barcode = IF(
  NEW.status = 'archived' OR NEW.barcode IS NULL OR TRIM(NEW.barcode) = '',
  NULL,
  NEW.barcode
);
