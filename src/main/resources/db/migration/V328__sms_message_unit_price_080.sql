-- Messages are listed to tenants at KES 0.80 each (was KES 1.00). Move the seeded
-- singleton over only while it still holds the old default, so an intentional
-- Super Admin price change is never clobbered, and update the column default for
-- any future singleton insert.
UPDATE platform_sms_credit_settings
   SET unit_price_kes = 0.80
 WHERE unit_price_kes = 1.00;

ALTER TABLE platform_sms_credit_settings
  MODIFY COLUMN unit_price_kes DECIMAL(12,2) NOT NULL DEFAULT 0.80;
