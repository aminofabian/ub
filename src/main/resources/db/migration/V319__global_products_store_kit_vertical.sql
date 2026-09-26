-- Vertical scoping for global catalog browse: a pharmacy must not see grocery SKUs and vice versa.
-- store_kit_id is the product's vertical (e.g. 'pharmacy') or the general 'grocery' bucket.
-- NULL means unrestricted (visible to every shop) — the safe default for future inserts.
--
-- Idempotent: a prior attempt may have added the column/index (MySQL DDL auto-commits)
-- then failed on the grocery UPDATE when trg_global_products_dedup_bu re-stamped
-- dedup_barcode from barcode onto seed rows that share barcodes (NULL dedup by design).
--
-- That trigger exists on MariaDB (V156) and on some local MySQL installs — always drop it
-- for the backfill. Recreate only on MariaDB: MySQL 8+/9 rejects CREATE TRIGGER inside
-- PREPARE (error 1295), and managed MySQL often rejects triggers entirely (error 1419).
-- App-layer GlobalProduct @PreUpdate already maintains dedup_barcode.

-- ---------------------------------------------------------------------------
-- Column + index (idempotent)
-- ---------------------------------------------------------------------------
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'global_products'
     AND COLUMN_NAME = 'store_kit_id') = 0,
  'ALTER TABLE global_products ADD COLUMN store_kit_id VARCHAR(64) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'global_products'
     AND INDEX_NAME = 'idx_global_products_catalog_store_kit') = 0,
  'CREATE INDEX idx_global_products_catalog_store_kit ON global_products (catalog_id, store_kit_id)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- Drop dedup UPDATE trigger for the duration of the backfill (any engine).
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_global_products_dedup_bu;

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

-- Restore MariaDB desktop safety-net only (same body as V156).
SET @is_mariadb := (SELECT IF(VERSION() LIKE '%MariaDB%', 1, 0));
SET @s := IF(
  @is_mariadb > 0,
  'CREATE TRIGGER trg_global_products_dedup_bu BEFORE UPDATE ON global_products FOR EACH ROW SET NEW.dedup_barcode = IF(NEW.status = ''archived'' OR NEW.barcode IS NULL OR TRIM(NEW.barcode) = '''', NULL, NEW.barcode)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
