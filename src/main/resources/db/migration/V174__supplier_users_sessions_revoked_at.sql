-- Force-logout cutoff: any supplier portal access token issued before this
-- instant is rejected, even when its supplier_user_sessions row is missing
-- (legacy tokens or best-effort session persistence failures).

ALTER TABLE supplier_users
  ADD COLUMN sessions_revoked_at TIMESTAMP(3) NULL;
