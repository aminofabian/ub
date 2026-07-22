-- Super Admin global catalog curation support:
--   * image_public_id for explicit clear/destroy of platform assets
--   * dedup_barcode unique among non-archived rows (archived / blank → NULL)
--
-- Managed MySQL (Coolify) with binary logging rejects CREATE TRIGGER (error 1419)
-- unless SUPER / log_bin_trust_function_creators. App-layer GlobalProduct
-- @PrePersist/@PreUpdate already maintains dedup_barcode — triggers are a
-- desktop MariaDB safety net only (same policy as V113).
--
-- Idempotent: first deploy may have partially applied (columns added, then
-- trigger failed). FlywayConfig repairThenMigrate re-runs this script.

-- ---------------------------------------------------------------------------
-- Columns (idempotent)
-- ---------------------------------------------------------------------------
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'global_products'
     AND COLUMN_NAME = 'image_public_id') = 0,
  'ALTER TABLE global_products ADD COLUMN image_public_id VARCHAR(512) NULL AFTER image_url',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'global_products'
     AND COLUMN_NAME = 'dedup_barcode') = 0,
  'ALTER TABLE global_products ADD COLUMN dedup_barcode VARCHAR(191) NULL AFTER image_public_id',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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
 WHERE gp.status <> 'archived'
   AND gp.dedup_barcode IS NULL;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'global_products'
     AND INDEX_NAME = 'uq_global_products_dedup_barcode'
);
SET @s := IF(
  @idx_exists = 0,
  'ALTER TABLE global_products ADD UNIQUE KEY uq_global_products_dedup_barcode (catalog_id, dedup_barcode)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- Triggers: MariaDB / desktop only (skip managed MySQL — error 1419)
-- ---------------------------------------------------------------------------
SET @is_mariadb := (SELECT IF(VERSION() LIKE '%MariaDB%', 1, 0));

SET @s := IF(@is_mariadb > 0, 'DROP TRIGGER IF EXISTS trg_global_products_dedup_bi', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(@is_mariadb > 0, 'DROP TRIGGER IF EXISTS trg_global_products_dedup_bu', 'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  @is_mariadb > 0,
  'CREATE TRIGGER trg_global_products_dedup_bi BEFORE INSERT ON global_products FOR EACH ROW SET NEW.dedup_barcode = IF(NEW.status = ''archived'' OR NEW.barcode IS NULL OR TRIM(NEW.barcode) = '''', NULL, NEW.barcode)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  @is_mariadb > 0,
  'CREATE TRIGGER trg_global_products_dedup_bu BEFORE UPDATE ON global_products FOR EACH ROW SET NEW.dedup_barcode = IF(NEW.status = ''archived'' OR NEW.barcode IS NULL OR TRIM(NEW.barcode) = '''', NULL, NEW.barcode)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
