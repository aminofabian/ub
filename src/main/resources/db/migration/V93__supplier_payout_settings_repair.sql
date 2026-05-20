-- Repair partial/failed V92: ensure column + table exist (no FK — avoids charset/engine mismatches).

ALTER TABLE platform_payment_gateways
  ADD COLUMN IF NOT EXISTS supplier_payout_supported BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE platform_payment_gateways
SET supplier_payout_supported = TRUE
WHERE gateway_type = 'KOPOKOPO'
  AND supplier_payout_supported = FALSE;

CREATE TABLE IF NOT EXISTS supplier_payout_settings (
  business_id                 CHAR(36) NOT NULL PRIMARY KEY,
  enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
  payment_gateway_config_id   CHAR(36) NULL,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
