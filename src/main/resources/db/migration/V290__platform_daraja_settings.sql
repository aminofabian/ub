-- Platform Safaricom Daraja (Paybill / Till STK) — credentials in SA DB only, never env.

CREATE TABLE platform_daraja_settings (
  id                              CHAR(36) PRIMARY KEY,
  enabled                         TINYINT(1) NOT NULL DEFAULT 0,
  environment                     VARCHAR(16) NOT NULL DEFAULT 'sandbox',
  shortcode_type                  VARCHAR(16) NOT NULL DEFAULT 'paybill',
  shortcode                       VARCHAR(32) NULL,
  credentials_enc                 TEXT NULL,
  updated_at                      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_daraja_settings (id) VALUES ('00000000-0000-0000-0000-000000000003');

-- Allow tenants to connect Daraja once the provider bean is live (SA still gates with is_enabled).
UPDATE platform_payment_gateways
SET description = 'Safaricom Daraja Lipa Na M-Pesa (STK / Paybill). Platform keys live under Super Admin → Payments; tenants may also BYO their own shortcode.'
WHERE gateway_type = 'DARAJA';
