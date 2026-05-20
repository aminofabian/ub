CREATE TABLE platform_payment_gateways (
  gateway_type  VARCHAR(32) PRIMARY KEY,
  is_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
  display_name  VARCHAR(100) NOT NULL,
  description   TEXT NULL,
  logo_url      VARCHAR(255) NULL,
  sort_order    INT NOT NULL DEFAULT 0,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO platform_payment_gateways (gateway_type, is_enabled, display_name, description, sort_order) VALUES
  ('KOPOKOPO', FALSE, 'KopoKopo', 'Accept M-Pesa payments via KopoKopo. No Safaricom developer account needed — simple API key setup.', 10),
  ('PAYSTACK', FALSE, 'Paystack', 'Accept card payments, M-Pesa, and other mobile money via Paystack.', 20),
  ('DARAJA',   FALSE, 'M-Pesa (Daraja)', 'Direct Safaricom Daraja integration. Requires your own Safaricom developer account.', 30),
  ('PESAPAL',  FALSE, 'PesaPal', 'Accept card and mobile money payments via PesaPal.', 40);
