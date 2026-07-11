-- Amount the customer handed over for cash sales (receipt display: Received / Change).
-- Accounting still posts payment amount = grand_total; this is receipt metadata only.

SET @tbl := 'sales';

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'cash_received') = 0,
  'ALTER TABLE sales ADD COLUMN cash_received DECIMAL(14,2) NULL AFTER grand_total',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
