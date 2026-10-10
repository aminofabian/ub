-- Model B — per-route Meta credentials, so a shop can bring its OWN Meta app/WABA.
--
-- A route normally rides the platform Meta app (super-admin keys). When `own_credentials` is set
-- the route carries its own encrypted access token / app secret / verify token + graph version,
-- and the channel uses them for that number: outbound sends with the route's token, and inbound
-- webhooks may be signed by (and verified against) any configured app secret.
--
-- Tenants supply their app secret + verify token alongside the number they already save in
-- Business → Configuration → WhatsApp (stored on business_credit_settings.*); `adopt` copies the
-- encrypted values onto the route. Add-only; MySQL/MariaDB.
-- See docs/scopes/whatsapp-crm/KEYS.md.

ALTER TABLE whatsapp_channel_route
  ADD COLUMN own_credentials BOOLEAN     NOT NULL DEFAULT FALSE,
  ADD COLUMN graph_version   VARCHAR(32) NULL,
  ADD COLUMN access_token_enc TEXT       NULL,
  ADD COLUMN app_secret_enc   TEXT       NULL,
  ADD COLUMN verify_token_enc TEXT       NULL;

ALTER TABLE business_credit_settings
  ADD COLUMN whatsapp_meta_app_secret_enc            TEXT NULL,
  ADD COLUMN whatsapp_meta_webhook_verify_token_enc  TEXT NULL;
