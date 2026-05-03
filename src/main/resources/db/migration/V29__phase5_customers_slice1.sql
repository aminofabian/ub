-- Phase 5 Slice 1 — customers, phones, credit_accounts (PHASE_5_PLAN.md §Slice 1, implement.md §5.8).
-- Tenant isolation via business_id; phone normalized in application layer (unique per business).

CREATE TABLE customers (
  id               CHAR(36)      PRIMARY KEY,
  business_id      CHAR(36)      NOT NULL,
  name             VARCHAR(500)  NOT NULL,
  email            VARCHAR(255)  NULL,
  notes            TEXT          NULL,
  version          BIGINT        NOT NULL DEFAULT 0,
  created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at       TIMESTAMP     NULL,
  CONSTRAINT fk_customers_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_customers_business_deleted (business_id, deleted_at),
  INDEX idx_customers_business_name (business_id, name(191))
);

CREATE TABLE customer_phones (
  id               CHAR(36)      PRIMARY KEY,
  business_id      CHAR(36)      NOT NULL,
  customer_id      CHAR(36)      NOT NULL,
  phone            VARCHAR(32)   NOT NULL,
  is_primary       BOOLEAN       NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_customer_phones_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
  CONSTRAINT fk_customer_phones_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_customer_phones_business_phone (business_id, phone),
  INDEX idx_customer_phones_customer (customer_id)
);

CREATE TABLE credit_accounts (
  id               CHAR(36)       PRIMARY KEY,
  business_id      CHAR(36)       NOT NULL,
  customer_id      CHAR(36)       NOT NULL,
  balance_owed     DECIMAL(14, 2)  NOT NULL DEFAULT 0,
  wallet_balance   DECIMAL(14, 2)  NOT NULL DEFAULT 0,
  loyalty_points   INT             NOT NULL DEFAULT 0,
  credit_limit     DECIMAL(14, 2)  NULL,
  last_activity_at TIMESTAMP       NULL,
  version          BIGINT          NOT NULL DEFAULT 0,
  created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_credit_accounts_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_credit_accounts_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE,
  UNIQUE KEY uq_credit_accounts_customer (customer_id)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000071', 'credits.customers.read',
   'List and view customers, phones, and credit profile totals.'),
  ('11111111-0000-0000-0000-000000000072', 'credits.customers.write',
   'Create and edit customers, phones, and credit limits.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000071'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000072'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000071'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000072'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000071'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000072'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000071'),
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000072'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000071');
