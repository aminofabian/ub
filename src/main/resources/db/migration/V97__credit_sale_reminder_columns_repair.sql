-- Per-column repair when V95/V96 did not fully apply (prevents 500 on sale-reminder-settings).
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.

SET @tbl := 'business_credit_settings';

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'credit_sale_reminder_enabled') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN credit_sale_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'credit_sale_reminder_payment_url') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN credit_sale_reminder_payment_url VARCHAR(512) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'rapidapi_key_enc') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN rapidapi_key_enc TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'whatsapp_meta_access_token_enc') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN whatsapp_meta_access_token_enc TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'whatsapp_meta_phone_number_id') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN whatsapp_meta_phone_number_id VARCHAR(64) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'whatsapp_meta_graph_version') = 0,
  CONCAT('ALTER TABLE business_credit_settings ADD COLUMN whatsapp_meta_graph_version VARCHAR(16) NOT NULL DEFAULT ', CHAR(39), 'v25.0', CHAR(39)),
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'sms_provider') = 0,
  CONCAT('ALTER TABLE business_credit_settings ADD COLUMN sms_provider VARCHAR(32) NOT NULL DEFAULT ', CHAR(39), 'none', CHAR(39)),
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'sms_africas_talking_username') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN sms_africas_talking_username VARCHAR(128) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = @tbl AND COLUMN_NAME = 'sms_africas_talking_api_key_enc') = 0,
  'ALTER TABLE business_credit_settings ADD COLUMN sms_africas_talking_api_key_enc TEXT NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE business_credit_settings
SET whatsapp_meta_graph_version = 'v25.0'
WHERE whatsapp_meta_graph_version IS NULL OR whatsapp_meta_graph_version = '';

UPDATE business_credit_settings
SET sms_provider = 'none'
WHERE sms_provider IS NULL OR sms_provider = '';
