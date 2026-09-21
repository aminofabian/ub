-- Phase 3: expense → KopoKopo Send Money (tenant BYO). Posted (books) ≠ Paid (money moved).
-- Idempotent ADD — safe if a prior partial deploy already added the column.

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'expenses' AND COLUMN_NAME = 'vendor_mpesa_number') = 0,
  'ALTER TABLE expenses ADD COLUMN vendor_mpesa_number VARCHAR(32) NULL AFTER payment_method',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS expense_disbursements (
  id                          VARCHAR(36) NOT NULL PRIMARY KEY,
  business_id                 VARCHAR(36) NOT NULL,
  expense_id                  VARCHAR(36) NOT NULL,
  occurrence_id               VARCHAR(36) NULL,
  schedule_id                 VARCHAR(36) NULL,
  gateway_type                VARCHAR(32) NOT NULL,
  payment_gateway_config_id   VARCHAR(36) NULL,
  kopokopo_send_money_id      VARCHAR(128) NULL,
  amount                      DECIMAL(14,2) NOT NULL,
  currency                    VARCHAR(8) NOT NULL DEFAULT 'KES',
  destination_type            VARCHAR(32) NOT NULL DEFAULT 'mobile_wallet',
  destination_phone           VARCHAR(32) NULL,
  status                      VARCHAR(24) NOT NULL DEFAULT 'pending',
  failure_reason              VARCHAR(512) NULL,
  metadata_json               TEXT NULL,
  created_at                  TIMESTAMP(6) NOT NULL,
  updated_at                  TIMESTAMP(6) NOT NULL,
  confirmed_at                TIMESTAMP(6) NULL,
  KEY idx_ed_business_status (business_id, status, created_at),
  KEY idx_ed_expense (expense_id, status),
  KEY idx_ed_kopokopo_id (kopokopo_send_money_id)
);
