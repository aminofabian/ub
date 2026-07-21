-- Sozuri SMS provider settings (alternative to Africa's Talking).

ALTER TABLE business_credit_settings
  ADD COLUMN sms_sozuri_project VARCHAR(128) NULL,
  ADD COLUMN sms_sozuri_api_key_enc TEXT NULL,
  ADD COLUMN sms_sozuri_from VARCHAR(64) NULL,
  ADD COLUMN sms_sozuri_type VARCHAR(32) NULL,
  ADD COLUMN sms_sozuri_api_url VARCHAR(512) NULL;
