-- In-progress checkout state per cart (guests) + durable guest profiles.

CREATE TABLE web_checkout_sessions (
  id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
  business_id           VARCHAR(36)  NOT NULL,
  cart_id               VARCHAR(36)  NOT NULL,
  guest_key             VARCHAR(64)  NULL,
  first_name            VARCHAR(120) NULL,
  last_name             VARCHAR(120) NULL,
  email                 VARCHAR(255) NULL,
  area_code             VARCHAR(16)  NULL,
  phone                 VARCHAR(64)  NULL,
  whatsapp              VARCHAR(64)  NULL,
  county                VARCHAR(120) NULL,
  subcounty             VARCHAR(120) NULL,
  ward                  VARCHAR(120) NULL,
  street_address        VARCHAR(500) NULL,
  delivery_notes        VARCHAR(1000) NULL,
  contact_completed_at  TIMESTAMP(6) NULL,
  delivery_completed_at TIMESTAMP(6) NULL,
  save_for_next_time    TINYINT(1)   NOT NULL DEFAULT 0,
  created_at            TIMESTAMP(6) NOT NULL,
  updated_at            TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_web_checkout_sessions_cart UNIQUE (cart_id),
  INDEX idx_web_checkout_sessions_business (business_id)
);

ALTER TABLE shopper_checkout_profiles
  MODIFY user_id VARCHAR(36) NULL,
  ADD COLUMN guest_key VARCHAR(64) NULL AFTER user_id,
  ADD CONSTRAINT uq_shopper_checkout_profiles_business_guest UNIQUE (business_id, guest_key);
