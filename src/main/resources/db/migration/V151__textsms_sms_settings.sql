-- TextSMS.co.ke SMS provider (third option alongside Sozuri / Africa's Talking).

ALTER TABLE platform_integration_settings
  ADD COLUMN textsms_partner_id VARCHAR(64) NULL,
  ADD COLUMN textsms_api_key_enc TEXT NULL,
  ADD COLUMN textsms_shortcode VARCHAR(64) NULL,
  ADD COLUMN textsms_api_url VARCHAR(512) NULL;

ALTER TABLE business_credit_settings
  ADD COLUMN sms_textsms_partner_id VARCHAR(64) NULL,
  ADD COLUMN sms_textsms_api_key_enc TEXT NULL,
  ADD COLUMN sms_textsms_shortcode VARCHAR(64) NULL,
  ADD COLUMN sms_textsms_api_url VARCHAR(512) NULL;
