-- Phase 8 Slice 5 follow-up — staff PII erasure: allow credentials to be cleared once anonymised_at is set.

ALTER TABLE users
  ADD COLUMN anonymised_at TIMESTAMP(3) NULL;

ALTER TABLE users
  DROP CHECK chk_users_credentials;

ALTER TABLE users
  ADD CONSTRAINT chk_users_credentials_v2 CHECK (
    anonymised_at IS NOT NULL
    OR password_hash IS NOT NULL
    OR pin_hash IS NOT NULL
  );
