-- Kiosk Pay: no platform markup; only provider (Paystack/KopoKopo) fees pass through.
-- MySQL-compatible (no PostgreSQL IS DISTINCT FROM / ALTER COLUMN SET DEFAULT).
ALTER TABLE platform_kiosk_pay_settings
  MODIFY fee_percent DECIMAL(6, 3) NOT NULL DEFAULT 0.000;

UPDATE platform_kiosk_pay_settings
SET fee_percent = 0.000
WHERE fee_percent <> 0.000;
