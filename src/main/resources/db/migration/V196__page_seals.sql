-- Page seals: PIN-protect public supplier passports and customer credit tabs.
ALTER TABLE marketplace_suppliers
  ADD COLUMN page_sealed TINYINT(1) NOT NULL DEFAULT 0 AFTER username,
  ADD COLUMN page_pin_hash VARCHAR(255) NULL AFTER page_sealed,
  ADD COLUMN page_seal_verified_at TIMESTAMP(6) NULL AFTER page_pin_hash,
  ADD COLUMN page_seal_updated_at TIMESTAMP(6) NULL AFTER page_seal_verified_at;

ALTER TABLE credit_accounts
  ADD COLUMN page_sealed TINYINT(1) NOT NULL DEFAULT 0 AFTER customer_id,
  ADD COLUMN page_pin_hash VARCHAR(255) NULL AFTER page_sealed,
  ADD COLUMN page_seal_verified_at TIMESTAMP(6) NULL AFTER page_pin_hash,
  ADD COLUMN page_seal_updated_at TIMESTAMP(6) NULL AFTER page_seal_verified_at;

CREATE TABLE page_seal_challenges (
  id                      VARCHAR(36)  NOT NULL PRIMARY KEY,
  scope                   VARCHAR(32)  NOT NULL,
  subject_id              VARCHAR(64)  NOT NULL,
  phone                   VARCHAR(32)  NOT NULL,
  code_hash               VARCHAR(64)  NOT NULL,
  expires_at              TIMESTAMP(6) NOT NULL,
  attempts                INT          NOT NULL DEFAULT 0,
  max_attempts            INT          NOT NULL DEFAULT 5,
  locked_until            TIMESTAMP(6) NULL,
  last_sent_at            TIMESTAMP(6) NULL,
  verified_at             TIMESTAMP(6) NULL,
  setup_token_hash        VARCHAR(64)  NULL,
  setup_token_expires_at  TIMESTAMP(6) NULL,
  consumed_at             TIMESTAMP(6) NULL,
  created_at              TIMESTAMP(6) NOT NULL,
  INDEX idx_page_seal_challenges_open (scope, subject_id, consumed_at, created_at),
  INDEX idx_page_seal_challenges_phone (phone, consumed_at)
);

CREATE TABLE page_seal_unlocks (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  scope        VARCHAR(32)  NOT NULL,
  subject_id   VARCHAR(64)  NOT NULL,
  token_hash   VARCHAR(64)  NOT NULL,
  expires_at   TIMESTAMP(6) NOT NULL,
  created_at   TIMESTAMP(6) NOT NULL,
  UNIQUE KEY uq_page_seal_unlock_token (token_hash),
  INDEX idx_page_seal_unlocks_subject (scope, subject_id, expires_at)
);
