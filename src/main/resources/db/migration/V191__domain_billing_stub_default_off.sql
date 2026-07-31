-- Real M-Pesa is the default path; billing stub must be opted into for testing.
-- Existing installs often left stub ON (previous default), which skips STK entirely.

ALTER TABLE platform_domain_settings
  MODIFY COLUMN hostafrica_billing_stub_enabled TINYINT(1) NOT NULL DEFAULT 0;

UPDATE platform_domain_settings
SET hostafrica_billing_stub_enabled = 0
WHERE id = '00000000-0000-0000-0000-000000000001'
  AND hostafrica_billing_stub_enabled = 1;
