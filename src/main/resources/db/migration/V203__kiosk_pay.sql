-- Kiosk Pay: platform custody wallet + SA settings + withdraw tracking.

CREATE TABLE platform_kiosk_pay_settings (
  id                              CHAR(36) PRIMARY KEY,
  enabled                         TINYINT(1) NOT NULL DEFAULT 0,
  fee_percent                     DECIMAL(6, 3) NOT NULL DEFAULT 1.000,
  min_withdraw_amount             DECIMAL(14, 2) NOT NULL DEFAULT 100.00,
  daily_withdraw_limit            DECIMAL(14, 2) NOT NULL DEFAULT 200000.00,
  currency                        VARCHAR(8) NOT NULL DEFAULT 'KES',
  paystack_environment            VARCHAR(16) NOT NULL DEFAULT 'sandbox',
  paystack_credentials_enc        TEXT NULL,
  kopokopo_environment            VARCHAR(16) NOT NULL DEFAULT 'sandbox',
  kopokopo_credentials_enc        TEXT NULL,
  updated_at                      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_kiosk_pay_settings (id) VALUES ('00000000-0000-0000-0000-000000000002');

CREATE TABLE kiosk_pay_accounts (
  id                              CHAR(36) PRIMARY KEY,
  business_id                     CHAR(36) NOT NULL,
  status                          VARCHAR(16) NOT NULL DEFAULT 'OFF',
  payout_phone                    VARCHAR(32) NULL,
  available_balance               DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  pending_balance                 DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  lifetime_in                     DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  lifetime_out                    DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  fee_percent_override            DECIMAL(6, 3) NULL,
  storefront_enabled              TINYINT(1) NOT NULL DEFAULT 1,
  created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_kpa_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_kpa_business (business_id),
  KEY idx_kpa_status (status)
);

CREATE TABLE kiosk_pay_ledger_entries (
  id                              CHAR(36) PRIMARY KEY,
  business_id                     CHAR(36) NOT NULL,
  account_id                      CHAR(36) NOT NULL,
  entry_type                      VARCHAR(32) NOT NULL,
  direction                       VARCHAR(8) NOT NULL,
  amount                          DECIMAL(14, 2) NOT NULL,
  currency                        VARCHAR(8) NOT NULL DEFAULT 'KES',
  available_delta                 DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  pending_delta                   DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  balance_after_available         DECIMAL(14, 2) NOT NULL,
  balance_after_pending           DECIMAL(14, 2) NOT NULL,
  reference                       VARCHAR(128) NULL,
  context_type                    VARCHAR(32) NULL,
  context_id                      CHAR(36) NULL,
  withdrawal_id                   CHAR(36) NULL,
  gateway_checkout_id             CHAR(36) NULL,
  note                            VARCHAR(512) NULL,
  created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_kple_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_kple_account FOREIGN KEY (account_id) REFERENCES kiosk_pay_accounts (id),
  UNIQUE KEY uq_kple_reference (reference),
  KEY idx_kple_account_created (account_id, created_at),
  KEY idx_kple_business_created (business_id, created_at)
);

CREATE TABLE kiosk_pay_withdrawals (
  id                              CHAR(36) PRIMARY KEY,
  business_id                     CHAR(36) NOT NULL,
  account_id                      CHAR(36) NOT NULL,
  amount                          DECIMAL(14, 2) NOT NULL,
  currency                        VARCHAR(8) NOT NULL DEFAULT 'KES',
  phone_number                    VARCHAR(32) NOT NULL,
  status                          VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',
  idempotency_key                 VARCHAR(64) NOT NULL,
  kopokopo_send_money_id          VARCHAR(128) NULL,
  failure_reason                  VARCHAR(512) NULL,
  requested_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processing_at                   TIMESTAMP NULL,
  completed_at                    TIMESTAMP NULL,
  created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_kpw_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_kpw_account FOREIGN KEY (account_id) REFERENCES kiosk_pay_accounts (id),
  UNIQUE KEY uq_kpw_idempotency (business_id, idempotency_key),
  KEY idx_kpw_status (status, created_at),
  KEY idx_kpw_send_money (kopokopo_send_money_id)
);
