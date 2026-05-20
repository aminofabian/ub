-- Supplier payout profile (KopoKopo Send Money) + disbursement tracking

ALTER TABLE suppliers
  ADD COLUMN payout_type VARCHAR(32) NOT NULL DEFAULT 'manual' AFTER payment_details,
  ADD COLUMN payout_phone VARCHAR(32) NULL AFTER payout_type,
  ADD COLUMN kopokopo_external_recipient_url VARCHAR(512) NULL AFTER payout_phone;

CREATE TABLE supplier_disbursements (
  id                          VARCHAR(36) NOT NULL PRIMARY KEY,
  business_id                 VARCHAR(36) NOT NULL,
  supplier_id                 VARCHAR(36) NOT NULL,
  supplier_invoice_id         VARCHAR(36) NOT NULL,
  gateway_type                VARCHAR(32) NOT NULL,
  payment_gateway_config_id   VARCHAR(36) NULL,
  kopokopo_send_money_id      VARCHAR(128) NULL,
  amount                      DECIMAL(14,2) NOT NULL,
  currency                    VARCHAR(8) NOT NULL DEFAULT 'KES',
  status                      VARCHAR(24) NOT NULL DEFAULT 'pending',
  failure_reason              VARCHAR(512) NULL,
  supplier_payment_id         VARCHAR(36) NULL,
  metadata_json               TEXT NULL,
  created_at                  TIMESTAMP(6) NOT NULL,
  updated_at                  TIMESTAMP(6) NOT NULL,
  confirmed_at                TIMESTAMP(6) NULL,
  KEY idx_sd_business_status (business_id, status, created_at),
  KEY idx_sd_invoice (supplier_invoice_id, status),
  KEY idx_sd_kopokopo_id (kopokopo_send_money_id)
);
