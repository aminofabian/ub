-- Legacy installs applied an older V46 that used column `day`; entities and current V46 use `business_day`.
-- Idempotent: no-op when `business_day` already exists (fresh installs).

SET @has_legacy_day := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mv_sales_daily'
      AND COLUMN_NAME = 'day'
);

SET @stmt := IF(
    @has_legacy_day > 0,
    'ALTER TABLE mv_sales_daily CHANGE COLUMN `day` `business_day` DATE NOT NULL',
    'SELECT 1'
);

PREPARE rename_day_if_needed FROM @stmt;
EXECUTE rename_day_if_needed;
DEALLOCATE PREPARE rename_day_if_needed;
