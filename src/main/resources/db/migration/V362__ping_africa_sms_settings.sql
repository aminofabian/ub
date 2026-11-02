-- Ping Africa Bulk SMS provider (fourth option alongside TextSMS / Sozuri / Africa's Talking).
-- Credentials are per-platform by default; business columns mirror the other providers so a
-- tenant can still override them from Credit tab → messaging settings.

ALTER TABLE platform_integration_settings
  ADD COLUMN pingafrica_api_key_enc TEXT NULL,
  ADD COLUMN pingafrica_base_url VARCHAR(512) NULL,
  ADD COLUMN pingafrica_sender_id VARCHAR(64) NULL;

ALTER TABLE business_credit_settings
  ADD COLUMN sms_pingafrica_api_key_enc TEXT NULL,
  ADD COLUMN sms_pingafrica_base_url VARCHAR(512) NULL,
  ADD COLUMN sms_pingafrica_sender_id VARCHAR(64) NULL;
