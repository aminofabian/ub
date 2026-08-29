-- Subscription billing, grace period & expiry — Phase 1
-- Scope: docs/scopes/SUBSCRIPTION_BILLING_SCOPE.md

ALTER TABLE businesses
  ADD COLUMN subscription_billing_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  ADD COLUMN current_period_end TIMESTAMP NULL,
  ADD COLUMN grace_started_at TIMESTAMP NULL,
  ADD COLUMN grace_ends_at TIMESTAMP NULL,
  ADD COLUMN billing_suspended_at TIMESTAMP NULL,
  ADD COLUMN suspension_reason VARCHAR(32) NULL;

ALTER TABLE businesses
  ADD CONSTRAINT businesses_subscription_billing_status_chk
  CHECK (subscription_billing_status IN ('ACTIVE', 'GRACE', 'SUSPENDED'));

-- Backfill: use legacy renews_at when present, else created_at + 30 days for paid tiers.
UPDATE businesses b
SET current_period_end = COALESCE(
      b.subscription_renews_at,
      CASE
        WHEN LOWER(b.subscription_tier) = 'free' THEN NULL
        ELSE DATE_ADD(b.created_at, INTERVAL 30 DAY)
      END
    )
WHERE b.current_period_end IS NULL
  AND b.deleted_at IS NULL;

CREATE TABLE platform_subscription_plans (
  tier_code           VARCHAR(64)   NOT NULL PRIMARY KEY,
  display_name        VARCHAR(128)  NOT NULL,
  monthly_price_kes   DECIMAL(12,2) NOT NULL DEFAULT 0,
  grace_days          INT           NOT NULL DEFAULT 15,
  product_limit       INT           NULL,
  cashier_limit       INT           NULL,
  active              BOOLEAN       NOT NULL DEFAULT TRUE,
  sort_order          INT           NOT NULL DEFAULT 0,
  updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_subscription_plans
  (tier_code, display_name, monthly_price_kes, grace_days, product_limit, cashier_limit, sort_order)
VALUES
  ('free',       'Free',       0.00,   15,   300,    1,    0),
  ('starter',    'Starter',  300.00,   15,  1000,    3,    1),
  ('business',   'Business', 800.00,   15,  2500,    5,    2),
  ('growth',     'Growth',  1500.00,   15,  5000,   10,    3),
  ('enterprise', 'Enterprise', 3000.00, 15, NULL, NULL,    4);

CREATE TABLE platform_subscription_billing_settings (
  id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
  billing_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
  default_grace_days    INT          NOT NULL DEFAULT 15,
  renewal_base_url      VARCHAR(512) NOT NULL DEFAULT 'https://palmart.co.ke/business/billing/renew',
  updated_at            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_subscription_billing_settings (id) VALUES ('00000000-0000-0000-0000-000000000003');

CREATE TABLE subscription_renewal_orders (
  id            VARCHAR(36)   NOT NULL PRIMARY KEY,
  business_id   VARCHAR(36)   NOT NULL,
  tier_code     VARCHAR(64)   NOT NULL,
  period_months INT           NOT NULL DEFAULT 1,
  amount_kes    DECIMAL(12,2) NOT NULL,
  status        VARCHAR(16)   NOT NULL,
  phone_number  VARCHAR(32)   NULL,
  stk_push_id   VARCHAR(36)   NULL,
  mpesa_receipt VARCHAR(64)   NULL,
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at       TIMESTAMP     NULL,
  updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscription_renewal_orders_business
  ON subscription_renewal_orders (business_id, created_at DESC);

CREATE INDEX idx_businesses_subscription_billing
  ON businesses (subscription_billing_status, current_period_end);
