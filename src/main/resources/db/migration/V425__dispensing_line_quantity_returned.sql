-- Pharmacy / clinic Phase 0 (ADR 0034): a refund adjusts the dispensing record.
--
-- A dispensing record line says what was handed over and is never rewritten. When some or all of
-- it is refunded, quantity_returned says how much came back, so the record, the balances-owed
-- list and the refills-due list stop describing a supply the patient no longer has.
--
-- Additive, nullable (NULL = nothing returned), no backfill: refunds made before this are not
-- reconstructed. Idempotent.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND COLUMN_NAME = 'quantity_returned') = 0,
  'ALTER TABLE dispensing_record_lines ADD COLUMN quantity_returned DECIMAL(14,4) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
