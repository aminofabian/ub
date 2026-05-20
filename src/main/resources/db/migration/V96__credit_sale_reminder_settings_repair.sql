-- Idempotent repair when V95 was not applied on a running deployment (prevents 500 on sale-reminder-settings API).

SET @db := DATABASE();

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db
    AND TABLE_NAME = 'business_credit_settings'
    AND COLUMN_NAME = 'credit_sale_reminder_enabled'
);

SET @ddl := IF(
  @col_exists = 0,
  'ALTER TABLE business_credit_settings
     ADD COLUMN credit_sale_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
     ADD COLUMN credit_sale_reminder_payment_url VARCHAR(512) NULL,
     ADD COLUMN rapidapi_key_enc TEXT NULL,
     ADD COLUMN whatsapp_meta_access_token_enc TEXT NULL,
     ADD COLUMN whatsapp_meta_phone_number_id VARCHAR(64) NULL,
     ADD COLUMN whatsapp_meta_graph_version VARCHAR(16) NOT NULL DEFAULT ''v25.0'',
     ADD COLUMN sms_provider VARCHAR(32) NOT NULL DEFAULT ''none'',
     ADD COLUMN sms_africas_talking_username VARCHAR(128) NULL,
     ADD COLUMN sms_africas_talking_api_key_enc TEXT NULL',
  'SELECT 1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
