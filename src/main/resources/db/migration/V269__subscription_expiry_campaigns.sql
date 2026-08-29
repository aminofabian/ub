-- Subscription expiry notification campaigns — Phase 3
-- Scope: docs/scopes/SUBSCRIPTION_BILLING_SCOPE.md §7

ALTER TABLE platform_subscription_billing_settings
  ADD COLUMN notification_cadence_days VARCHAR(128) NOT NULL DEFAULT '0,2,5,8,11,13,14,15';

CREATE TABLE subscription_expiry_campaigns (
  id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
  business_id        VARCHAR(36)  NOT NULL,
  grace_started_at   TIMESTAMP    NOT NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  last_step_day      INT          NOT NULL DEFAULT -1,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  cancelled_at       TIMESTAMP    NULL,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_subscription_expiry_campaigns_active
  ON subscription_expiry_campaigns (status, business_id);

CREATE TABLE subscription_expiry_campaign_deliveries (
  id           VARCHAR(36)  NOT NULL PRIMARY KEY,
  campaign_id  VARCHAR(36)  NOT NULL,
  step_day     INT          NOT NULL,
  channel      VARCHAR(8)   NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  detail       VARCHAR(512) NULL,
  sent_at      TIMESTAMP    NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_subscription_expiry_delivery UNIQUE (campaign_id, step_day, channel)
);

CREATE INDEX idx_subscription_expiry_deliveries_campaign
  ON subscription_expiry_campaign_deliveries (campaign_id, step_day);
