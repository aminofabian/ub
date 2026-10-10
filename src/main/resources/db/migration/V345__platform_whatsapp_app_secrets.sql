-- Additional platform Meta app secrets.
--
-- A WhatsApp Business Account can have more than one Meta app subscribed, and Meta signs each
-- webhook delivery with the secret of the app that sent it. Inbound verification must therefore
-- accept any subscribed app's secret. The primary secret stays on
-- platform_integration_settings.whatsapp_meta_app_secret_enc; this table holds additional ones so a
-- second Meta app can be accepted without replacing the first. Add-only; MySQL/MariaDB.

CREATE TABLE platform_whatsapp_app_secret (
  id             CHAR(36)     NOT NULL PRIMARY KEY,
  label          VARCHAR(120) NULL,
  app_secret_enc TEXT         NOT NULL,
  created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
