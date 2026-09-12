-- AI logo generation credits: 1 free kit per shop, then prepaid credits
-- (same purchased SMS credit pool; default 50 credits per generate).

ALTER TABLE platform_sms_credit_settings
  ADD COLUMN ai_logo_free_allowance INT NOT NULL DEFAULT 1,
  ADD COLUMN ai_logo_credit_cost INT NOT NULL DEFAULT 50;

CREATE TABLE business_ai_logo_usage (
  business_id VARCHAR(36) NOT NULL PRIMARY KEY,
  free_used   INT         NOT NULL DEFAULT 0,
  paid_count  INT         NOT NULL DEFAULT 0,
  version     BIGINT      NOT NULL DEFAULT 0,
  updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
