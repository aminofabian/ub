-- Repair: ensure sales.receipt_no exists if V137 failed mid-flight or was skipped.
-- Safe to re-run; follows V97/V133 idempotent column pattern.

SET @tbl := 'sales';

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'receipt_no') = 0,
  'ALTER TABLE sales ADD COLUMN receipt_no BIGINT NULL AFTER business_id',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE sales s
INNER JOIN (
  SELECT id, rn FROM (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY business_id ORDER BY created_at, id) AS rn
    FROM sales
  ) AS ranked
) AS src ON src.id = s.id
SET s.receipt_no = src.rn
WHERE s.receipt_no IS NULL;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND INDEX_NAME = 'uq_sales_business_receipt_no') = 0,
  'ALTER TABLE sales ADD UNIQUE KEY uq_sales_business_receipt_no (business_id, receipt_no)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
