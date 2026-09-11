-- Customer Intelligence Warehouse V1 §8.4: owner-pinned tags on a customer
-- (prefer tags over a boolean so "staff" / "family" can come later).
-- JSON array string, e.g. '["wholesale"]'.

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customers'
     AND COLUMN_NAME = 'tags') = 0,
  'ALTER TABLE customers ADD COLUMN tags JSON NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
