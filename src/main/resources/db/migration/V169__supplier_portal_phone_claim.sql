-- Supplier self-serve claim: phone login + OTP challenges.

ALTER TABLE supplier_users
  MODIFY COLUMN email VARCHAR(191) NULL,
  ADD COLUMN phone VARCHAR(32) NULL AFTER email;

CREATE UNIQUE INDEX uq_supplier_users_phone ON supplier_users (phone);

CREATE TABLE supplier_phone_verifications (
  id                            CHAR(36)     PRIMARY KEY,
  phone                         VARCHAR(32)  NOT NULL,
  code_hash                     VARCHAR(64)  NOT NULL,
  expires_at                    TIMESTAMP    NOT NULL,
  attempts                      INT          NOT NULL DEFAULT 0,
  max_attempts                  INT          NOT NULL DEFAULT 5,
  consumed_at                   TIMESTAMP    NULL,
  verified_at                   TIMESTAMP    NULL,
  setup_token_hash              VARCHAR(64)  NULL,
  setup_token_expires_at        TIMESTAMP    NULL,
  created_at                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_sent_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_supplier_phone_verifications_phone_created (phone, created_at),
  INDEX idx_supplier_phone_verifications_setup_token (setup_token_hash)
);
