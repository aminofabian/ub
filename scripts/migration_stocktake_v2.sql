-- =============================================================================
-- Stocktake v2 Migration
-- Adds session types (morning/evening), line status, checklist, and
-- one-per-type-per-branch-per-day constraint.
-- =============================================================================

-- 1. Add columns to stock_take_sessions
ALTER TABLE stock_take_sessions
  ADD COLUMN IF NOT EXISTS session_type VARCHAR(16) NOT NULL DEFAULT 'morning',
  ADD COLUMN IF NOT EXISTS session_date DATE NOT NULL DEFAULT CURRENT_DATE,
  ADD COLUMN IF NOT EXISTS closed_by    VARCHAR(36);

-- Backfill existing rows
UPDATE stock_take_sessions
   SET session_type = 'morning',
       session_date = CAST(created_at AS DATE)
 WHERE session_type = 'morning'
   AND session_date = CURRENT_DATE; -- only touches rows that got the default

-- 2. Add columns to stock_take_lines
ALTER TABLE stock_take_lines
  ADD COLUMN IF NOT EXISTS status          VARCHAR(16)  NOT NULL DEFAULT 'pending',
  ADD COLUMN IF NOT EXISTS aisle           VARCHAR(255),
  ADD COLUMN IF NOT EXISTS submitted_by    VARCHAR(36),
  ADD COLUMN IF NOT EXISTS submitted_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS confirmed_by    VARCHAR(36),
  ADD COLUMN IF NOT EXISTS confirmed_at    TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS admin_quantity  DECIMAL(14,4);

-- 3. Create stocktake_checklist_items table
CREATE TABLE IF NOT EXISTS stocktake_checklist_items (
    business_id   VARCHAR(36) NOT NULL,
    item_id       VARCHAR(36) NOT NULL,
    session_type  VARCHAR(16) NOT NULL DEFAULT 'both',
    sort_order    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (business_id, item_id),
    CONSTRAINT fk_checklist_item
      FOREIGN KEY (item_id) REFERENCES items(id)
);

-- Seed checklist from existing stocked, active items
INSERT INTO stocktake_checklist_items (business_id, item_id, session_type, sort_order)
SELECT i.business_id, i.id, 'both', 0
  FROM items i
 WHERE i.stocked = true
   AND i.deleted_at IS NULL
   AND NOT EXISTS (
     SELECT 1 FROM stocktake_checklist_items c
      WHERE c.business_id = i.business_id AND c.item_id = i.id
   );

-- 4. Add unique constraint for one-session-per-type-per-branch-per-day
-- First, check if the constraint already exists (idempotent)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'uq_stocktake_branch_type_date'
           AND conrelid = 'stock_take_sessions'::regclass
    ) THEN
        ALTER TABLE stock_take_sessions
          ADD CONSTRAINT uq_stocktake_branch_type_date
          UNIQUE (business_id, branch_id, session_type, session_date);
    END IF;
END $$;
