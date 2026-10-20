-- Super Admin control over automatic subscription blocking.
--
-- Default TRUE preserves the existing behaviour. When FALSE, expiry still
-- moves a shop through grace and shows warnings, but never suspends the tenant
-- or revokes sessions. Manual tenant/user suspensions remain unaffected.

ALTER TABLE platform_subscription_billing_settings
  ADD COLUMN auto_block_enabled BOOLEAN NOT NULL DEFAULT TRUE;
