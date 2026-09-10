-- OpenRouter (GLM and other models) for SokoMind chat + logo generation.
-- Idempotent: a killed deploy can leave columns without a Flyway success row.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'openrouter_api_key_enc') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN openrouter_api_key_enc TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'openrouter_base_url') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN openrouter_base_url VARCHAR(512) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'openrouter_mini_model') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN openrouter_mini_model VARCHAR(128) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'openrouter_smart_model') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN openrouter_smart_model VARCHAR(128) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'platform_sokomind_settings'
     AND COLUMN_NAME = 'openrouter_image_model') = 0,
  'ALTER TABLE platform_sokomind_settings ADD COLUMN openrouter_image_model VARCHAR(128) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
