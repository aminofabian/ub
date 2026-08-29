-- Subscription billing Phase 4 — pre-expiry reminders, annual pricing, dunning support
-- Scope: docs/scopes/SUBSCRIPTION_BILLING_SCOPE.md §16 Phase 4

ALTER TABLE platform_subscription_plans
  ADD COLUMN annual_price_kes DECIMAL(12,2) NULL;

-- Default annual = 10 months of monthly (pay 10, get 12)
UPDATE platform_subscription_plans
SET annual_price_kes = monthly_price_kes * 10
WHERE tier_code <> 'free'
  AND monthly_price_kes > 0;

ALTER TABLE platform_subscription_billing_settings
  ADD COLUMN pre_expiry_reminder_days INT NOT NULL DEFAULT 7;

CREATE TABLE subscription_pre_expiry_notifications (
  id             VARCHAR(36)  NOT NULL PRIMARY KEY,
  business_id    VARCHAR(36)  NOT NULL,
  period_end_at  TIMESTAMP    NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  detail         VARCHAR(512) NULL,
  sent_at        TIMESTAMP    NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_subscription_pre_expiry UNIQUE (business_id, period_end_at)
);

CREATE INDEX idx_subscription_pre_expiry_business
  ON subscription_pre_expiry_notifications (business_id, period_end_at DESC);
