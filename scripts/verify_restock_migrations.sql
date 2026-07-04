-- =============================================================================
-- Verify daily audit + restock Flyway migrations on production MySQL.
-- Run against the PalMart API database (same DB as spring.datasource.url).
--
-- Usage (replace credentials/host/db):
--   mysql -h HOST -u USER -p DATABASE < backend/scripts/verify_restock_migrations.sql
-- =============================================================================

SELECT '--- Flyway: latest inventory/stock-take migrations ---' AS section;

SELECT version, description, installed_on, success
  FROM flyway_schema_history
 WHERE version IN ('133', '134')
    OR description LIKE '%daily_stock_audit%'
    OR description LIKE '%stock_take_restock%'
 ORDER BY installed_rank;

SELECT '--- Expected: version 133 (daily_stock_audit) and 134 (stock_take_restock_items) ---' AS section;

SELECT '--- Table exists checks ---' AS section;

SELECT TABLE_NAME, TABLE_ROWS, CREATE_TIME, UPDATE_TIME
  FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME IN (
         'daily_stock_audits',
         'daily_stock_audit_items',
         'stock_take_restock_items'
       )
 ORDER BY TABLE_NAME;

SELECT '--- stock_take_restock_items columns (sample) ---' AS section;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'stock_take_restock_items'
 ORDER BY ORDINAL_POSITION;

SELECT '--- stock_take_sessions daily-audit columns ---' AS section;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'stock_take_sessions'
   AND COLUMN_NAME IN ('source', 'daily_audit_id', 'current_line_index')
 ORDER BY COLUMN_NAME;

SELECT '--- Latest Flyway version overall ---' AS section;

SELECT version, description, installed_on
  FROM flyway_schema_history
 WHERE success = 1
 ORDER BY installed_rank DESC
 LIMIT 5;
