-- Lower Kiosk Pay minimum withdraw to KES 20.
ALTER TABLE platform_kiosk_pay_settings
  MODIFY min_withdraw_amount DECIMAL(14, 2) NOT NULL DEFAULT 20.00;

UPDATE platform_kiosk_pay_settings
SET min_withdraw_amount = 20.00
WHERE min_withdraw_amount > 20.00;
