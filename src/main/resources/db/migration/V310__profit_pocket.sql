-- Profit Pocket: owner/expense destination (separate from customer-pay till) + pocket audit.

CREATE TABLE IF NOT EXISTS profit_pocket_settings (
  business_id                 CHAR(36) NOT NULL PRIMARY KEY,
  enabled                     BOOLEAN NOT NULL DEFAULT FALSE,
  destination_type            VARCHAR(32) NULL,
  destination_label           VARCHAR(120) NULL,
  destination_account         VARCHAR(64) NULL,
  destination_bank_name       VARCHAR(120) NULL,
  destination_paybill         VARCHAR(32) NULL,
  destination_paybill_account VARCHAR(64) NULL,
  default_float               DECIMAL(14, 2) NOT NULL DEFAULT 5000.00,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS profit_pockets (
  id                          CHAR(36) NOT NULL PRIMARY KEY,
  business_id                 CHAR(36) NOT NULL,
  branch_id                   CHAR(36) NULL,
  period_from                 DATE NOT NULL,
  period_to                   DATE NOT NULL,
  amount                      DECIMAL(14, 2) NOT NULL,
  leave_float                 DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  suggested_surplus           DECIMAL(14, 2) NULL,
  funding_method              VARCHAR(32) NOT NULL DEFAULT 'cash',
  destination_type            VARCHAR(32) NOT NULL,
  destination_snapshot_json   TEXT NOT NULL,
  warnings_json               TEXT NULL,
  journal_entry_id            CHAR(36) NULL,
  created_by                  CHAR(36) NOT NULL,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_profit_pockets_business_created (business_id, created_at)
);
