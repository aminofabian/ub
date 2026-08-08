-- Kiosk Pay: pause withdrawals when the platform Send Money float is insufficient.
-- Card collections settle to Paystack, so the KopoKopo till can be temporarily dry
-- even while tenant ledger balances are positive.
ALTER TABLE platform_kiosk_pay_settings
  ADD COLUMN send_money_float_constrained_until TIMESTAMP NULL;
