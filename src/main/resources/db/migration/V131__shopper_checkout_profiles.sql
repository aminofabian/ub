-- Saved checkout contact + delivery profile for signed-in shoppers (per tenant).

CREATE TABLE shopper_checkout_profiles (
  id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
  business_id           VARCHAR(36)  NOT NULL,
  user_id               VARCHAR(36)  NOT NULL,
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
  is_default            TINYINT(1)   NOT NULL DEFAULT 0,
  created_at            TIMESTAMP(6) NOT NULL,
  updated_at            TIMESTAMP(6) NOT NULL,
  CONSTRAINT uq_shopper_checkout_profiles_business_user UNIQUE (business_id, user_id),
  INDEX idx_shopper_checkout_profiles_user (user_id)
);
