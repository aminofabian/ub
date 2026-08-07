-- Opt-in scheduled auto-pay for unpaid supply bills via KopoKopo Send Money.

ALTER TABLE supplier_payout_settings
  ADD COLUMN auto_pay_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER payment_gateway_config_id;
