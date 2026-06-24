-- Adds per-account balance reminder tracking for the 3-day recurring reminder sweep.
-- MySQL-compatible: uses information_schema checks + PREPARE/EXECUTE because
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.

SET @tbl := 'credit_accounts';

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'last_balance_reminder_at') = 0,
  'ALTER TABLE credit_accounts ADD COLUMN last_balance_reminder_at TIMESTAMP NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'balance_reminder_count') = 0,
  'ALTER TABLE credit_accounts ADD COLUMN balance_reminder_count INT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = @tbl
    AND INDEX_NAME = 'idx_credit_accounts_balance_reminder');

SET @s := IF(@idx_exists = 0,
  'CREATE INDEX idx_credit_accounts_balance_reminder
     ON credit_accounts (business_id, reminders_opt_out, last_balance_reminder_at, balance_reminder_count)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
