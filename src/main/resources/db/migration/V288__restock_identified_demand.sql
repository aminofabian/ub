-- =============================================================================
-- V288 — Warehouse V3: identified-demand explain (+ optional qty nudge)
-- on nightly restock suggestions. Velocity evidence / suggested_qty stay the
-- engine; these columns are additive and nullable so old runs stay valid.
-- =============================================================================

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'restock_suggestions'
     AND COLUMN_NAME = 'identified_due_qty') = 0,
  "ALTER TABLE restock_suggestions ADD COLUMN identified_due_qty DECIMAL(14,4) NULL",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'restock_suggestions'
     AND COLUMN_NAME = 'identified_explain') = 0,
  "ALTER TABLE restock_suggestions ADD COLUMN identified_explain VARCHAR(512) NULL",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
