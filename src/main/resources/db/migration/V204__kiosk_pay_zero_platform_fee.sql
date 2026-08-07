-- Kiosk Pay: no platform markup; only provider (Paystack/KopoKopo) fees pass through.
ALTER TABLE platform_kiosk_pay_settings
  ALTER COLUMN fee_percent SET DEFAULT 0;

UPDATE platform_kiosk_pay_settings
SET fee_percent = 0
WHERE fee_percent IS DISTINCT FROM 0;
