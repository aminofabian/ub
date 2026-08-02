-- Enable ops alerts for tenants who already verified a phone before auto-enable-on-verify.
UPDATE business_ops_alert_settings
SET enabled = 1
WHERE phone IS NOT NULL
  AND TRIM(phone) <> ''
  AND phone_verified_at IS NOT NULL
  AND enabled = 0;
