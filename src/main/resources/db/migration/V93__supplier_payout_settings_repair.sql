-- Repair partial/failed V92: ensure column + table exist (MySQL-compatible, idempotent).
-- MySQL does not support ALTER TABLE ... ADD COLUMN IF NOT EXISTS.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'platform_payment_gateways'
     AND COLUMN_NAME = 'supplier_payout_supported') = 0,
  'ALTER TABLE platform_payment_gateways ADD COLUMN supplier_payout_supported BOOLEAN NOT NULL DEFAULT FALSE',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

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
