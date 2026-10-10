-- Bind each conversation to the Meta number it arrived on, so outbound replies leave from the
-- shop's own number instead of the platform default. Populated on ingest; existing rows stay NULL
-- and fall back to the shop's single routed number (then the platform default). Add-only; MySQL/MariaDB.

ALTER TABLE crm_conversation ADD COLUMN phone_number_id VARCHAR(64) NULL;
