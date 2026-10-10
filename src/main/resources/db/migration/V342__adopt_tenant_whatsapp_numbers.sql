-- Adopt numbers tenants already entered as WhatsApp channel routes.
--
-- Shops fill in their Meta "phone number ID" under Business → Configuration → WhatsApp (stored on
-- `business_credit_settings.whatsapp_meta_phone_number_id`). This one-time backfill promotes those
-- to `whatsapp_channel_route` rows so super-admin does not have to re-key every shop's number.
--
-- New saves after this are adopted automatically by `BusinessCreditMessagingSettingsService`.
-- `INSERT IGNORE` skips numbers already routed (`phone_number_id` is UNIQUE). Add-only; MySQL/MariaDB.

INSERT IGNORE INTO whatsapp_channel_route
  (id, phone_number_id, business_id, label, status, created_at, updated_at)
SELECT UUID(), TRIM(bcs.whatsapp_meta_phone_number_id), bcs.business_id,
       'From tenant settings', 'active', NOW(), NOW()
FROM business_credit_settings bcs
WHERE bcs.whatsapp_meta_phone_number_id IS NOT NULL
  AND TRIM(bcs.whatsapp_meta_phone_number_id) <> '';
