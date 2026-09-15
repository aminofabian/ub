-- Add a dedicated wastage_reason column for enum-based reporting.
-- Existing reason text is preserved; new rows populate both.
-- Idempotent: skips if column already exists (handles partial runs).

SET @db_name = (SELECT DATABASE());

SET @col_exists = (SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db_name
    AND TABLE_NAME   = 'stock_movements'
    AND COLUMN_NAME  = 'wastage_reason');

SET @sql_add_col = IF(@col_exists = 0,
  'ALTER TABLE stock_movements
    ADD COLUMN wastage_reason VARCHAR(32) NULL AFTER reason',
  'SELECT ''Column wastage_reason already exists, skipping ADD COLUMN'' AS _info');

PREPARE stmt_add FROM @sql_add_col;
EXECUTE stmt_add;
DEALLOCATE PREPARE stmt_add;

-- Backfill existing wastage rows with a best-effort match
UPDATE stock_movements
   SET wastage_reason = 'OTHER'
 WHERE movement_type = 'wastage'
   AND wastage_reason IS NULL;

-- Plain index only. MySQL has no partial/filtered indexes — the same rule V4 already
-- states for user_sessions — so `CREATE INDEX … WHERE <predicate>` is a syntax error
-- and this script could never apply to a fresh MySQL (>= 8.0.13).
--
-- It passed on MariaDB 10.x purely by accident: VERSION() returns '10.11.x', which
-- compared as a *string* sorts below '8.0.13', so the old version gate picked the
-- plain-index branch. The filter was never applied on any engine.
--
-- Idempotent: skips if the index already exists.
SET @idx_exists = (SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db_name
    AND TABLE_NAME   = 'stock_movements'
    AND INDEX_NAME   = 'idx_sm_wastage_reason');

SET @sql_create_idx = IF(@idx_exists = 0,
  'CREATE INDEX idx_sm_wastage_reason
     ON stock_movements (business_id, wastage_reason, created_at)',
  'SELECT ''Index idx_sm_wastage_reason already exists, skipping'' AS _info');

PREPARE stmt_idx FROM @sql_create_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;
