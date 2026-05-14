-- =============================================================================
-- V73 — Stocktake v2: seed checklist, unique constraint, column backfill.
-- All idempotent — safe to re-run after partial application.
-- =============================================================================

-- 1. Backfill session_date for any existing sessions that got the default
UPDATE stock_take_sessions
   SET session_date = DATE(created_at)
 WHERE session_date = CURRENT_DATE
   AND DATE(created_at) <> CURRENT_DATE;

-- 2. Seed checklist from existing stocked, active items
INSERT IGNORE INTO stocktake_checklist_items (business_id, item_id, session_type, sort_order)
SELECT i.business_id, i.id, 'both', 0
  FROM items i
 WHERE i.is_stocked = true
   AND i.deleted_at IS NULL;

-- 3. Add unique constraint: one session per type per branch per day (idempotent)
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
