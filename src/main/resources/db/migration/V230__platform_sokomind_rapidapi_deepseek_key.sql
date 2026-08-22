-- PlatformSokoMindSettings gained a RapidAPI DeepSeek key column (entity field
-- added in commit 79a3502) without a matching migration. Hibernate schema
-- validation (`ddl-auto=validate`) then aborts startup with:
--   "Schema validation: missing column [rapidapi_deepseek_api_key_enc] in table
--    [platform_sokomind_settings]"
--
-- Added idempotently: MySQL/MariaDB lack `ADD COLUMN IF NOT EXISTS`, and a
-- killed deploy can leave the column in place without a Flyway success row —
-- the plain ALTER would then fail on the next boot.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'rapidapi_deepseek_api_key_enc') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN rapidapi_deepseek_api_key_enc TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
