-- Add closed_at / closed_by columns missing from V61 (SupplyBatch entity has them).
-- Idempotent: skips if already present.

SET @db_name = (SELECT DATABASE());

SET @col_exists = (SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db_name
    AND TABLE_NAME   = 'supply_batches'
    AND COLUMN_NAME  = 'closed_at');

SET @sql_add = IF(@col_exists = 0,
  'ALTER TABLE supply_batches
    ADD COLUMN closed_at      TIMESTAMP   NULL AFTER updated_at,
    ADD COLUMN closed_by      VARCHAR(36) NULL AFTER closed_at',
  'SELECT ''closed_at / closed_by already exist, skipping'' AS _info');

PREPARE stmt FROM @sql_add;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
