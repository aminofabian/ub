-- Desktop supplies sync (scopes/DESKTOP_SUPPLIERS_SYNC_SCOPE.md §4 "push"):
-- marker for Path B supply sessions recorded on the till that still need to be
-- uploaded to the online shop (mirror of suppliers.cloud_synced_at from V275).
-- Sessions pulled DOWN from the cloud are stamped with the same column so a
-- mirrored supply is never pushed back up. Idempotent for MySQL 8.4 / MariaDB.
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'raw_purchase_sessions' AND COLUMN_NAME = 'cloud_synced_at') = 0,
  'ALTER TABLE raw_purchase_sessions ADD COLUMN cloud_synced_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
