-- Repair Path B draft columns if an earlier non-idempotent V154 partially applied
-- (MySQL DDL autocommit can leave line columns without client_draft_json, or vice versa).

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'raw_purchase_lines'
     AND COLUMN_NAME = 'draft_qty') = 0,
  'ALTER TABLE raw_purchase_lines ADD COLUMN draft_qty DECIMAL(14,4) NULL AFTER suggested_item_id',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'raw_purchase_lines'
     AND COLUMN_NAME = 'draft_unit_cost') = 0,
  'ALTER TABLE raw_purchase_lines ADD COLUMN draft_unit_cost DECIMAL(14,4) NULL AFTER draft_qty',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'raw_purchase_lines'
     AND COLUMN_NAME = 'draft_sell_price') = 0,
  'ALTER TABLE raw_purchase_lines ADD COLUMN draft_sell_price DECIMAL(14,4) NULL AFTER draft_unit_cost',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'raw_purchase_lines'
     AND COLUMN_NAME = 'draft_expiry_date') = 0,
  'ALTER TABLE raw_purchase_lines ADD COLUMN draft_expiry_date DATE NULL AFTER draft_sell_price',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'raw_purchase_sessions'
     AND COLUMN_NAME = 'client_draft_json') = 0,
  'ALTER TABLE raw_purchase_sessions ADD COLUMN client_draft_json TEXT NULL AFTER notes',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
