-- Platform M-Pesa STK for Kenyan domain purchases (Palmart till, not tenant till).

ALTER TABLE platform_domain_settings
  ADD COLUMN palmart_stk_credentials_enc TEXT NULL AFTER hostafrica_billing_stub_enabled,
  ADD COLUMN palmart_stk_till_number VARCHAR(32) NULL AFTER palmart_stk_credentials_enc;

ALTER TABLE domain_orders
  ADD COLUMN paid_at TIMESTAMP NULL AFTER last_error,
  ADD COLUMN payment_checkout_id VARCHAR(128) NULL AFTER paid_at,
  ADD COLUMN payment_txn_id VARCHAR(128) NULL AFTER payment_checkout_id,
  ADD COLUMN payer_phone VARCHAR(32) NULL AFTER payment_txn_id,
  ADD COLUMN last_stk_status VARCHAR(32) NULL AFTER payer_phone;
