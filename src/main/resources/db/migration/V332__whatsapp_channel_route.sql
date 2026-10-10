-- M1 — WhatsApp channel: route Meta numbers to shops.
--
-- Meta credentials themselves stay in platform_integration_settings (super-admin);
-- this table only maps a Meta phone_number_id to the owning business so the channel
-- adapter can route inbound/outbound traffic. One platform Meta app can serve many
-- numbers — this is the multi-tenant mechanism.
--
-- The tenant (business_id) comes from this table at ingest time; an unmapped or paused
-- number is left unrouted for super-admin triage, never guessed.
-- See docs/scopes/whatsapp-crm/SCOPE.md §6.2 and docs/adr/0011-whatsapp-crm-boundary.md.

CREATE TABLE whatsapp_channel_route (
  id              CHAR(36) PRIMARY KEY,
  phone_number_id VARCHAR(64) NOT NULL,
  business_id     CHAR(36) NOT NULL,
  display_number  VARCHAR(32) NULL,
  label           VARCHAR(120) NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'active',
  quality_rating  VARCHAR(32) NULL,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_wcr_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_wcr_phone_number (phone_number_id),
  KEY idx_wcr_business (business_id)
);
