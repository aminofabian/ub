-- Desktop web-order sync: marker for orders confirmed at the till that still
-- need to be uploaded to the online shop, and for orders mirrored down from
-- the cloud (so a mirrored order is never pushed back up). Mirrors
-- raw_purchase_sessions.cloud_synced_at from V277. Idempotent for MySQL 8.4 /
-- MariaDB.
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'web_orders' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE web_orders ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
