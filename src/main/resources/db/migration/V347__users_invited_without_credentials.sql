-- Staff invites have no password or PIN until the person sets one from the
-- email link. chk_users_credentials_v2 rejected that insert, which surfaced
-- as "Could not persist the requested change" on POST /api/v1/users.

ALTER TABLE users
  DROP CONSTRAINT chk_users_credentials_v2;

ALTER TABLE users
  ADD CONSTRAINT chk_users_credentials_v3 CHECK (
    anonymised_at IS NOT NULL
    OR status = 'invited'
    OR password_hash IS NOT NULL
    OR pin_hash IS NOT NULL
  );
