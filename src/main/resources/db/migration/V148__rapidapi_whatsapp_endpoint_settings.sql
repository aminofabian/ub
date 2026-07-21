-- Platform + tenant overrides for WhatsApp RapidAPI lookup endpoint (host, URL, phone JSON field).

ALTER TABLE platform_integration_settings
  ADD COLUMN rapidapi_whatsapp_host VARCHAR(255) NULL,
  ADD COLUMN rapidapi_whatsapp_lookup_url VARCHAR(512) NULL,
  ADD COLUMN rapidapi_whatsapp_phone_field VARCHAR(64) NULL,
  ADD COLUMN rapidapi_whatsapp_phone_digits_only TINYINT(1) NULL;

ALTER TABLE business_credit_settings
  ADD COLUMN rapidapi_host VARCHAR(255) NULL,
  ADD COLUMN rapidapi_lookup_url VARCHAR(512) NULL,
  ADD COLUMN rapidapi_phone_field VARCHAR(64) NULL,
  ADD COLUMN rapidapi_phone_digits_only TINYINT(1) NULL;
