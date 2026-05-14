-- =============================================================================
-- V73 — Stocktake v2: add session columns, checklist table, seed data,
--        backfill, and unique constraint.
-- All idempotent — safe to re-run after partial application.
-- MySQL-compatible: uses information_schema checks + PREPARE/EXECUTE because
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.
-- =============================================================================

-- ── 1. Add columns to stock_take_sessions ────────────────────────────
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'session_type') = 0,
  CONCAT('ALTER TABLE stock_take_sessions ADD COLUMN session_type VARCHAR(16) NOT NULL DEFAULT ', CHAR(39), 'morning', CHAR(39)),
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'session_date') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN session_date DATE NOT NULL DEFAULT (CURRENT_DATE)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'closed_by') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN closed_by VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ── 2. Create stocktake_checklist_items table ────────────────────────
CREATE TABLE IF NOT EXISTS stocktake_checklist_items (
    business_id  VARCHAR(36) NOT NULL,
    item_id      VARCHAR(36) NOT NULL,
    session_type VARCHAR(16) NOT NULL DEFAULT 'both',
    sort_order   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (business_id, item_id),
    CONSTRAINT fk_checklist_item FOREIGN KEY (item_id) REFERENCES items(id)
);

-- ── 3. Backfill session_date for existing sessions ───────────────────
UPDATE stock_take_sessions
   SET session_date = DATE(created_at)
 WHERE session_date = CURRENT_DATE
   AND DATE(created_at) <> CURRENT_DATE;

-- ── 4. Seed checklist from existing stocked, active items ────────────
INSERT IGNORE INTO stocktake_checklist_items (business_id, item_id, session_type, sort_order)
SELECT i.business_id, i.id, 'both', 0
  FROM items i
 WHERE i.is_stocked = true
   AND i.deleted_at IS NULL;

-- ── 5. Add unique constraint: one session per type per branch per day ──
SET @constraint_exists := (
  SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_sessions'
     AND constraint_name = 'uq_stocktake_branch_type_date'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE stock_take_sessions ADD CONSTRAINT uq_stocktake_branch_type_date UNIQUE (business_id, branch_id, session_type, session_date)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
