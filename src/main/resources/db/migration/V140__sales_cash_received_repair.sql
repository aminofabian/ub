-- Repair: ensure sales.cash_received exists if V139 failed or was skipped.

SET @tbl := 'sales';

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'cash_received') = 0,
  'ALTER TABLE sales ADD COLUMN cash_received DECIMAL(14,2) NULL AFTER grand_total',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
