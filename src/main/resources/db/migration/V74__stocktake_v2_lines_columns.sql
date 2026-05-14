-- =============================================================================
-- V74 — Stocktake v2: Add line status, admin quantity, and confirmation tracking.
-- MySQL-compatible: uses information_schema checks + PREPARE/EXECUTE because
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.
-- Each block is idempotent — safe to run even if a column already exists.
-- =============================================================================

-- ── stock_take_sessions: session_type ────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'session_type') = 0,
  CONCAT('ALTER TABLE stock_take_sessions ADD COLUMN session_type VARCHAR(16) NOT NULL DEFAULT ', CHAR(39), 'morning', CHAR(39)),
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_sessions: session_date ────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'session_date') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN session_date DATE NOT NULL DEFAULT (CURRENT_DATE)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── stock_take_sessions: closed_by ───────────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'closed_by') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN closed_by VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

-- ── stocktake_checklist_items table ──────────────────────────────────
-- CREATE TABLE IF NOT EXISTS is supported in MySQL unlike ADD COLUMN IF NOT EXISTS.
CREATE TABLE IF NOT EXISTS stocktake_checklist_items (
    business_id  VARCHAR(36) NOT NULL,
    item_id      VARCHAR(36) NOT NULL,
    session_type VARCHAR(16) NOT NULL DEFAULT 'both',
    sort_order   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (business_id, item_id),
    CONSTRAINT fk_checklist_item FOREIGN KEY (item_id) REFERENCES items(id)
);
