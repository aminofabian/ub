-- Desktop supplier sync: marker for suppliers created/edited on the till that
-- still need to be uploaded to the online shop (mirror of
-- customers.cloud_synced_at). Idempotent for MySQL 8.4 / MariaDB 10.11.
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'suppliers' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE suppliers ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
