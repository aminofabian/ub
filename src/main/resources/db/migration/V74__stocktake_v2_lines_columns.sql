-- =============================================================================
-- V74 — Stocktake v2: Add line status, aisle, admin quantity, and confirmation tracking.
-- MySQL-compatible: uses information_schema checks + PREPARE/EXECUTE because
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.
-- Each block is idempotent — safe to run even if a column already exists.
-- =============================================================================

-- ── stock_take_lines: status ─────────────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'status') = 0,
  CONCAT('ALTER TABLE stock_take_lines ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ', CHAR(39), 'pending', CHAR(39)),
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: aisle ──────────────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'aisle') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN aisle VARCHAR(255)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: submitted_by ───────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'submitted_by') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN submitted_by VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: submitted_at ───────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'submitted_at') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN submitted_at TIMESTAMP NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: confirmed_by ───────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'confirmed_by') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN confirmed_by VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: confirmed_at ───────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'confirmed_at') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN confirmed_at TIMESTAMP NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_lines: admin_quantity ─────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'admin_quantity') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN admin_quantity DECIMAL(14,4)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
