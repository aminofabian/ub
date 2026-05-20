-- Tenant-controlled supplier payouts (Send Money) + platform capability per gateway type.

ALTER TABLE platform_payment_gateways
  ADD COLUMN supplier_payout_supported BOOLEAN NOT NULL DEFAULT FALSE AFTER is_enabled;

UPDATE platform_payment_gateways
SET supplier_payout_supported = TRUE
WHERE gateway_type = 'KOPOKOPO';

CREATE TABLE supplier_payout_settings (
  business_id                 VARCHAR(36) NOT NULL PRIMARY KEY,
  enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
  payment_gateway_config_id   VARCHAR(36) NULL,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_sps_gateway_config
    FOREIGN KEY (payment_gateway_config_id) REFERENCES payment_gateway_configs (id)
    ON DELETE SET NULL
);
