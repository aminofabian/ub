-- Daraja on a public shop is a scam magnet. Active Daraja stays on the till
-- (cash remains the cashier default). The online shop stays off until the
-- merchant requests it and Super Admin approves.
-- OFF | PENDING | APPROVED | REJECTED

ALTER TABLE payment_gateway_configs
  ADD COLUMN storefront_approval VARCHAR(16) NOT NULL DEFAULT 'OFF' AFTER is_default;
