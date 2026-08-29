-- SMS Credits & Monthly Quotas — Phase 1 (scope: docs/scopes/SMS_CREDITS_SCOPE.md)
-- Metering at the single platform SMS send choke point, monthly included allowance per
-- subscription tier, purchased top-up balance (rolls over), SA config + audit ledger.

-- 1. Permissions: buy credits (owner/admin) + read ledger (settings readers).
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000711', 'sms.credits.purchase',
   'Buy SMS credit top-ups for this business.'),
  ('11111111-0000-0000-0000-000000000712', 'sms.credits.ledger.read',
   'View the SMS credit ledger (movements) for this business.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000711'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000711'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000712'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000712'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000712');

-- 2. Platform-wide SMS credit configuration (singleton row, edited in Super Admin).
CREATE TABLE platform_sms_credit_settings (
  id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
  enabled               BOOLEAN       NOT NULL DEFAULT TRUE,
  unit_price_kes        DECIMAL(12,2) NOT NULL DEFAULT 1.00,
  min_purchase_credits  INT           NOT NULL DEFAULT 10,
  max_purchase_credits  INT           NOT NULL DEFAULT 500,
  low_balance_threshold INT           NOT NULL DEFAULT 5,
  cycle_timezone        VARCHAR(64)   NOT NULL DEFAULT 'Africa/Nairobi',
  updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_sms_credit_settings (id) VALUES ('00000000-0000-0000-0000-000000000002');

-- 3. Included SMS per subscription tier (editable in Super Admin without deploy).
CREATE TABLE platform_sms_tier_allowances (
  tier_code              VARCHAR(64) NOT NULL PRIMARY KEY,
  included_sms_per_month INT         NOT NULL,
  active                 BOOLEAN     NOT NULL DEFAULT TRUE,
  updated_at             TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_sms_tier_allowances (tier_code, included_sms_per_month) VALUES
  ('starter',   30),
  ('business',  50),
  ('growth',    100),
  ('enterprise',100);

-- 4. Per-tenant SMS credit account (one row per business).
CREATE TABLE business_sms_credit_accounts (
  business_id       VARCHAR(36) NOT NULL PRIMARY KEY,
  included_used     INT         NOT NULL DEFAULT 0,
  included_override INT         NULL,
  purchased_balance INT         NOT NULL DEFAULT 0,
  cycle_started_at  TIMESTAMP   NOT NULL,
  version           BIGINT      NOT NULL DEFAULT 0,
  updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. Immutable audit trail of every credit movement.
CREATE TABLE sms_credit_ledger (
  id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
  business_id        VARCHAR(36)  NOT NULL,
  delta              INT          NOT NULL,
  balance_after      INT          NOT NULL,
  kind               VARCHAR(32)  NOT NULL,
  reason             VARCHAR(64)  NULL,
  reference_id       VARCHAR(128) NULL,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by_user_id VARCHAR(36)  NULL
);

CREATE INDEX idx_sms_credit_ledger_business ON sms_credit_ledger (business_id, created_at DESC);

-- 6. Credit top-up checkouts (platform M-Pesa STK).
CREATE TABLE sms_credit_purchases (
  id            VARCHAR(36)   NOT NULL PRIMARY KEY,
  business_id   VARCHAR(36)   NOT NULL,
  credits       INT           NOT NULL,
  amount_kes    DECIMAL(12,2) NOT NULL,
  status        VARCHAR(16)   NOT NULL,
  phone_number  VARCHAR(32)   NULL,
  stk_push_id   VARCHAR(36)   NULL,
  mpesa_receipt VARCHAR(64)   NULL,
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at       TIMESTAMP     NULL,
  updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sms_credit_purchases_business ON sms_credit_purchases (business_id, created_at DESC);
