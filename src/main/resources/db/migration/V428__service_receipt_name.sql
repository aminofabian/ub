-- Clinic (ADR 0034, decision 4): an optional name a service is sold under.
--
-- A receipt is handed over and left lying around. A clinic can give a service a plainer name for
-- the receipt and the sale ("Laboratory test") while staff keep the real one on their screens.
-- NULL means the service is sold under its own name, as it always has been.
--
-- Additive, nullable, no backfill. Idempotent.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'services' AND COLUMN_NAME = 'receipt_name') = 0,
  'ALTER TABLE services ADD COLUMN receipt_name VARCHAR(191) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
