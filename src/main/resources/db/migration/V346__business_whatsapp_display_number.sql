-- Remember the WhatsApp number a shop typed so the console can echo it back.
--
-- Shops used to enter a Meta phone_number_id (an internal id). With the shared platform WABA they
-- now enter their plain WhatsApp number, and the backend resolves it to the phone_number_id that
-- drives routing (business_credit_settings.whatsapp_meta_phone_number_id). This column keeps the
-- number they entered. Add-only; MySQL/MariaDB.

ALTER TABLE business_credit_settings
  ADD COLUMN whatsapp_meta_display_number VARCHAR(32) NULL;
