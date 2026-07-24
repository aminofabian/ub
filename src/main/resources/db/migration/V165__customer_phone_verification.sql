-- Phone OTP for POS new-number credit registration.

ALTER TABLE customer_phones
  ADD COLUMN verified_at TIMESTAMP NULL AFTER is_primary;

CREATE TABLE customer_phone_verifications (
  id                            CHAR(36)     PRIMARY KEY,
  business_id                   CHAR(36)     NOT NULL,
  phone                         VARCHAR(32)  NOT NULL,
  code_hash                     VARCHAR(64)  NOT NULL,
  expires_at                    TIMESTAMP    NOT NULL,
  attempts                      INT          NOT NULL DEFAULT 0,
  max_attempts                  INT          NOT NULL DEFAULT 5,
  consumed_at                   TIMESTAMP    NULL,
  verified_at                   TIMESTAMP    NULL,
  registration_token_hash       VARCHAR(64)  NULL,
  registration_token_expires_at TIMESTAMP    NULL,
  created_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_sent_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_customer_phone_verifications_business
    FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_customer_phone_verifications_biz_phone_created
    (business_id, phone, created_at),
  INDEX idx_customer_phone_verifications_reg_token
    (business_id, registration_token_hash)
);
