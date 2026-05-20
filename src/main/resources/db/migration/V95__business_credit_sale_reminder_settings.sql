-- Per-tenant credit tab sale reminder + messaging credentials (admin-configured).

ALTER TABLE business_credit_settings
  ADD COLUMN credit_sale_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN credit_sale_reminder_payment_url VARCHAR(512) NULL,
  ADD COLUMN rapidapi_key_enc TEXT NULL,
  ADD COLUMN whatsapp_meta_access_token_enc TEXT NULL,
  ADD COLUMN whatsapp_meta_phone_number_id VARCHAR(64) NULL,
  ADD COLUMN whatsapp_meta_graph_version VARCHAR(16) NOT NULL DEFAULT 'v25.0',
  ADD COLUMN sms_provider VARCHAR(32) NOT NULL DEFAULT 'none',
  ADD COLUMN sms_africas_talking_username VARCHAR(128) NULL,
  ADD COLUMN sms_africas_talking_api_key_enc TEXT NULL;
