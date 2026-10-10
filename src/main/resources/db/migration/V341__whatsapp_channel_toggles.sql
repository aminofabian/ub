-- M1 (console toggle) — runtime on/off for the WhatsApp channel.
--
-- Nullable on purpose: NULL means "inherit the deployment flag"
-- (app.integrations.whatsapp.enabled / .outbox.enabled). Once a super-admin flips a switch in
-- Super Admin → Platform → WhatsApp numbers the stored value wins, so the channel can be turned
-- on (or off) from the console without a redeploy. Add-only; MySQL/MariaDB.
-- See docs/scopes/whatsapp-crm/KEYS.md.

ALTER TABLE platform_integration_settings
  ADD COLUMN whatsapp_channel_enabled BOOLEAN NULL,
  ADD COLUMN whatsapp_outbox_enabled  BOOLEAN NULL;
