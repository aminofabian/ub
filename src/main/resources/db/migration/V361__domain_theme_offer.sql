-- "Own your address": custom domain + storefront theme offer at KES 1,000.
--
-- Super Admin launches one campaign. Every eligible shop (live, no custom
-- domain) gets a tokenised pay link by email and in the in-app bell, then two
-- more touches three days apart. The link is public and safe to forward: it
-- carries no staff session and only exposes the shop's public identity.
--
-- KES 1,000 covers the setup and theme customisation. If the shop does not own
-- a domain yet, a new .co.ke registration stays a separate KES 2,000 purchase
-- through the existing domain order flow (registration_status tracks that).

CREATE TABLE IF NOT EXISTS domain_offer_campaigns (
  id                        CHAR(36) NOT NULL PRIMARY KEY,
  name                      VARCHAR(191) NOT NULL,
  status                    VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  audience_key              VARCHAR(64) NOT NULL DEFAULT 'no_custom_domain',
  price_cents               BIGINT NOT NULL DEFAULT 100000,
  currency                  VARCHAR(8) NOT NULL DEFAULT 'KES',
  interval_days             INT NOT NULL DEFAULT 3,
  max_touches               INT NOT NULL DEFAULT 3,
  email_enabled             BOOLEAN NOT NULL DEFAULT TRUE,
  in_app_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
  recipients_targeted       INT NOT NULL DEFAULT 0,
  created_by_super_admin_id CHAR(36) NULL,
  started_at                TIMESTAMP NULL,
  paused_at                 TIMESTAMP NULL,
  completed_at              TIMESTAMP NULL,
  created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS domain_offer_orders (
  id                  CHAR(36) NOT NULL PRIMARY KEY,
  campaign_id         CHAR(36) NOT NULL,
  business_id         CHAR(36) NOT NULL,
  reference           VARCHAR(24) NOT NULL,
  pay_token           VARCHAR(64) NOT NULL,
  status              VARCHAR(32) NOT NULL DEFAULT 'INVITED',
  domain_mode         VARCHAR(24) NOT NULL DEFAULT 'OWNED',
  requested_domain    VARCHAR(255) NULL,
  suggested_domain    VARCHAR(255) NULL,
  theme_key           VARCHAR(64) NULL,
  theme_direction     VARCHAR(32) NULL,
  theme_notes         TEXT NULL,
  contact_name        VARCHAR(191) NULL,
  contact_email       VARCHAR(191) NULL,
  contact_phone       VARCHAR(32) NULL,
  amount_cents        BIGINT NOT NULL DEFAULT 100000,
  currency            VARCHAR(8) NOT NULL DEFAULT 'KES',
  touch_count         INT NOT NULL DEFAULT 0,
  last_touch_at       TIMESTAMP NULL,
  next_touch_at       TIMESTAMP NULL,
  last_touch_kind     VARCHAR(24) NULL,
  email_sent_count    INT NOT NULL DEFAULT 0,
  in_app_sent_count   INT NOT NULL DEFAULT 0,
  last_email_error    VARCHAR(500) NULL,
  stk_push_id         CHAR(36) NULL,
  payment_checkout_id VARCHAR(128) NULL,
  mpesa_receipt       VARCHAR(128) NULL,
  payer_phone         VARCHAR(32) NULL,
  last_stk_status     VARCHAR(32) NULL,
  last_error          VARCHAR(500) NULL,
  payment_started_at  TIMESTAMP NULL,
  paid_at             TIMESTAMP NULL,
  sla_due_at          TIMESTAMP NULL,
  assigned_to         VARCHAR(191) NULL,
  started_at          TIMESTAMP NULL,
  delivered_at        TIMESTAMP NULL,
  delivered_domain    VARCHAR(255) NULL,
  domain_mapping_id   CHAR(36) NULL,
  domain_order_id     CHAR(36) NULL,
  registration_status VARCHAR(24) NULL,
  fulfilment_notes    TEXT NULL,
  cancelled_at        TIMESTAMP NULL,
  cancel_reason       VARCHAR(500) NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at          TIMESTAMP NULL,
  CONSTRAINT fk_domain_offer_campaign FOREIGN KEY (campaign_id) REFERENCES domain_offer_campaigns(id),
  CONSTRAINT fk_domain_offer_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_domain_offer_mapping FOREIGN KEY (domain_mapping_id) REFERENCES domains(id),
  CONSTRAINT uq_domain_offer_campaign_business UNIQUE (campaign_id, business_id),
  CONSTRAINT uq_domain_offer_reference UNIQUE (reference),
  CONSTRAINT uq_domain_offer_token UNIQUE (pay_token),
  CONSTRAINT uq_domain_offer_receipt UNIQUE (mpesa_receipt)
);

CREATE INDEX idx_domain_offer_status ON domain_offer_orders (status);
CREATE INDEX idx_domain_offer_business ON domain_offer_orders (business_id);
CREATE INDEX idx_domain_offer_due ON domain_offer_orders (campaign_id, status, next_touch_at);
CREATE INDEX idx_domain_offer_paid ON domain_offer_orders (paid_at);
