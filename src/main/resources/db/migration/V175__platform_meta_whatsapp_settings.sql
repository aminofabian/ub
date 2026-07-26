-- Meta WhatsApp Cloud API platform defaults (editable in Super Admin → Platform integrations).
-- Env vars remain last-resort fallbacks.

ALTER TABLE platform_integration_settings
  ADD COLUMN whatsapp_meta_access_token_enc TEXT NULL,
  ADD COLUMN whatsapp_meta_phone_number_id VARCHAR(64) NULL,
  ADD COLUMN whatsapp_meta_graph_version VARCHAR(32) NULL,
  ADD COLUMN whatsapp_meta_webhook_verify_token_enc TEXT NULL,
  ADD COLUMN whatsapp_meta_app_secret_enc TEXT NULL;
