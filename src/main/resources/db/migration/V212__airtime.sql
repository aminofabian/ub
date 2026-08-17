-- Airtime resale: platform Instalipa credentials + per-tenant opt-in + order tracking.
-- Tenants spend their Kiosk Pay wallet balance; the platform holds the single
-- Instalipa float that every tenant draws down.

CREATE TABLE platform_airtime_settings (
  id                              CHAR(36) PRIMARY KEY,
  enabled                         TINYINT(1) NOT NULL DEFAULT 0,
  provider                        VARCHAR(24) NOT NULL DEFAULT 'INSTALIPA',
  base_url                        VARCHAR(255) NOT NULL DEFAULT 'https://business.instalipa.co.ke',
  environment                     VARCHAR(16) NOT NULL DEFAULT 'sandbox',
  credentials_enc                 TEXT NULL,
  -- Share of face value credited back to the tenant as their selling margin.
  tenant_commission_percent       DECIMAL(6, 3) NOT NULL DEFAULT 3.000,
  min_amount                      DECIMAL(14, 2) NOT NULL DEFAULT 5.00,
  max_amount                      DECIMAL(14, 2) NOT NULL DEFAULT 5000.00,
  daily_tenant_limit              DECIMAL(14, 2) NOT NULL DEFAULT 50000.00,
  currency                        VARCHAR(8) NOT NULL DEFAULT 'KES',
  pos_enabled                     TINYINT(1) NOT NULL DEFAULT 1,
  storefront_enabled              TINYINT(1) NOT NULL DEFAULT 1,
  -- Last float balance Instalipa reported on any response, for ops visibility.
  float_balance                   DECIMAL(14, 2) NULL,
  float_low_threshold             DECIMAL(14, 2) NOT NULL DEFAULT 5000.00,
  float_checked_at                TIMESTAMP NULL,
  -- While set (future timestamp), sends fail fast — the platform float is dry.
  float_constrained_until         TIMESTAMP NULL,
  updated_at                      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_airtime_settings (id) VALUES ('00000000-0000-0000-0000-000000000001');

CREATE TABLE business_airtime_settings (
  id                              CHAR(36) PRIMARY KEY,
  business_id                     CHAR(36) NOT NULL,
  enabled                         TINYINT(1) NOT NULL DEFAULT 0,
  pos_enabled                     TINYINT(1) NOT NULL DEFAULT 1,
  storefront_enabled              TINYINT(1) NOT NULL DEFAULT 0,
  -- Tenant's own ceiling per transaction; the platform max still applies.
  max_single_amount               DECIMAL(14, 2) NULL,
  created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_bas_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_bas_business (business_id)
);

CREATE TABLE airtime_orders (
  id                              CHAR(36) PRIMARY KEY,
  business_id                     CHAR(36) NOT NULL,
  branch_id                       CHAR(36) NULL,
  channel                         VARCHAR(16) NOT NULL,
  phone_number                    VARCHAR(32) NOT NULL,
  network                         VARCHAR(16) NULL,
  -- Face value sent to the subscriber.
  amount                          DECIMAL(14, 2) NOT NULL,
  -- Wallet debit (equals amount; held then settled).
  cost                            DECIMAL(14, 2) NOT NULL,
  -- Margin credited back to the tenant wallet on success.
  commission                      DECIMAL(14, 2) NOT NULL DEFAULT 0.00,
  commission_percent              DECIMAL(6, 3) NOT NULL DEFAULT 0.000,
  currency                        VARCHAR(8) NOT NULL DEFAULT 'KES',
  status                          VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
  reference                       VARCHAR(64) NOT NULL,
  idempotency_key                 VARCHAR(64) NOT NULL,
  provider_transaction_id         VARCHAR(128) NULL,
  provider_status                 VARCHAR(32) NULL,
  provider_details                VARCHAR(255) NULL,
  provider_discount               DECIMAL(14, 2) NULL,
  provider_balance                DECIMAL(14, 2) NULL,
  receipt                         VARCHAR(64) NULL,
  failure_reason                  VARCHAR(512) NULL,
  sale_id                         CHAR(36) NULL,
  web_order_id                    CHAR(36) NULL,
  customer_id                     CHAR(36) NULL,
  cashier_user_id                 CHAR(36) NULL,
  -- Storefront orders only dispatch once the shopper's payment is captured.
  paid_at                         TIMESTAMP NULL,
  requested_at                    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  submitted_at                    TIMESTAMP NULL,
  completed_at                    TIMESTAMP NULL,
  created_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ao_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_ao_idempotency (business_id, idempotency_key),
  UNIQUE KEY uq_ao_reference (reference),
  KEY idx_ao_business_created (business_id, created_at),
  KEY idx_ao_status_created (status, created_at),
  KEY idx_ao_provider_txn (provider_transaction_id),
  KEY idx_ao_phone (business_id, phone_number)
);

-- ---------- Permissions -----------------------------------------------------

INSERT INTO permissions (id, permission_key, description) VALUES
  ('c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b60', 'airtime.read',
   'View airtime sales history and float status.'),
  ('c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b61', 'airtime.sell',
   'Sell airtime from the till or dashboard against the Kiosk Pay wallet.'),
  ('c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b62', 'airtime.manage',
   'Enable airtime resale and change its limits for this business.');

-- Owner + Admin: read, sell, manage. Manager: read, sell. Cashier: read, sell.
INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b60'),
  ('22222222-0000-0000-0000-000000000001', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b61'),
  ('22222222-0000-0000-0000-000000000001', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b62'),
  ('22222222-0000-0000-0000-000000000002', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b60'),
  ('22222222-0000-0000-0000-000000000002', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b61'),
  ('22222222-0000-0000-0000-000000000002', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b62'),
  ('22222222-0000-0000-0000-000000000003', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b60'),
  ('22222222-0000-0000-0000-000000000003', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b61'),
  ('22222222-0000-0000-0000-000000000004', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b60'),
  ('22222222-0000-0000-0000-000000000004', 'c4b1e6d7-8a92-4f31-b5c0-1d2e3f4a5b61');
