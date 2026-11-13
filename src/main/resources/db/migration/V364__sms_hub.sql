-- SMS Hub (docs/scopes/SMS_HUB_SCOPE.md, ADR 0013).
--   1. Marketing consent on the customer (default UNKNOWN = receipts and order updates only).
--   2. sms_messages: one row per tenant SMS that reaches the send choke point.
--   3. customer_sms_campaigns (+ recipients): SMS twin of customer_email_campaigns, with scheduling.
--   4. business_sms_hub_settings: per-shop automation toggles and wording.
-- Additive only. Idempotent for MySQL 8.4 / MariaDB 10.11 (desktop runs this too).

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customers'
     AND COLUMN_NAME = 'sms_marketing_status') = 0,
  'ALTER TABLE customers
     ADD COLUMN sms_marketing_status VARCHAR(16) NOT NULL DEFAULT ''UNKNOWN'',
     ADD COLUMN sms_marketing_source VARCHAR(32) NULL,
     ADD COLUMN sms_marketing_updated_at TIMESTAMP(3) NULL',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS sms_messages (
  id                   CHAR(36)      PRIMARY KEY,
  business_id          CHAR(36)      NOT NULL,
  customer_id          CHAR(36)      NULL,
  phone                VARCHAR(32)   NOT NULL,
  body                 VARCHAR(1600) NOT NULL,
  parts                INT           NOT NULL DEFAULT 1,
  credits              INT           NOT NULL DEFAULT 0,
  message_class        VARCHAR(16)   NOT NULL DEFAULT 'TRANSACTIONAL',
  reason               VARCHAR(64)   NULL,
  reference_id         VARCHAR(128)  NULL,
  campaign_id          CHAR(36)      NULL,
  provider             VARCHAR(32)   NULL,
  provider_message_id  VARCHAR(128)  NULL,
  status               VARCHAR(16)   NOT NULL,
  error                VARCHAR(500)  NULL,
  created_at           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  delivered_at         TIMESTAMP(3)  NULL,
  CONSTRAINT fk_sms_messages_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_sms_messages_business_created (business_id, created_at),
  INDEX idx_sms_messages_business_customer (business_id, customer_id, created_at),
  INDEX idx_sms_messages_business_phone (business_id, phone, message_class, created_at),
  INDEX idx_sms_messages_business_reference (business_id, reference_id),
  INDEX idx_sms_messages_campaign (campaign_id),
  INDEX idx_sms_messages_provider_ref (provider_message_id),
  INDEX idx_sms_messages_phone_created (phone, created_at)
);

CREATE TABLE IF NOT EXISTS customer_sms_campaigns (
  id                   CHAR(36)      PRIMARY KEY,
  business_id          CHAR(36)      NOT NULL,
  name                 VARCHAR(255)  NOT NULL,
  body                 VARCHAR(1000) NOT NULL,
  recipient_method     VARCHAR(32)   NOT NULL,
  filter_json          TEXT          NULL,
  status               VARCHAR(16)   NOT NULL,
  scheduled_at         TIMESTAMP(3)  NULL,
  recipients_targeted  INT           NOT NULL DEFAULT 0,
  recipients_sent      INT           NOT NULL DEFAULT 0,
  recipients_failed    INT           NOT NULL DEFAULT 0,
  recipients_skipped   INT           NOT NULL DEFAULT 0,
  credits_used         INT           NOT NULL DEFAULT 0,
  created_by_user_id   CHAR(36)      NULL,
  created_at           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at           TIMESTAMP(3)  NULL,
  completed_at         TIMESTAMP(3)  NULL,
  heartbeat_at         TIMESTAMP(3)  NULL,
  CONSTRAINT fk_customer_sms_campaigns_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_customer_sms_campaigns_business_created (business_id, created_at),
  INDEX idx_customer_sms_campaigns_due (status, scheduled_at)
);

CREATE TABLE IF NOT EXISTS customer_sms_campaign_recipients (
  id              CHAR(36)      PRIMARY KEY,
  campaign_id     CHAR(36)      NOT NULL,
  business_id     CHAR(36)      NOT NULL,
  customer_id     CHAR(36)      NOT NULL,
  phone           VARCHAR(32)   NOT NULL,
  customer_name   VARCHAR(500)  NULL,
  status          VARCHAR(16)   NOT NULL,
  skip_reason     VARCHAR(64)   NULL,
  error           VARCHAR(500)  NULL,
  parts           INT           NOT NULL DEFAULT 0,
  sent_at         TIMESTAMP(3)  NULL,
  CONSTRAINT fk_customer_sms_recipients_campaign FOREIGN KEY (campaign_id) REFERENCES customer_sms_campaigns (id),
  CONSTRAINT fk_customer_sms_recipients_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_customer_sms_recipients_campaign_customer (campaign_id, customer_id),
  INDEX idx_customer_sms_recipients_campaign_status (campaign_id, status)
);

CREATE TABLE IF NOT EXISTS business_sms_hub_settings (
  business_id          CHAR(36)      PRIMARY KEY,
  order_notices_json   TEXT          NULL,
  thank_you_enabled    TINYINT(1)    NOT NULL DEFAULT 0,
  thank_you_template   VARCHAR(480)  NULL,
  winback_enabled      TINYINT(1)    NOT NULL DEFAULT 0,
  winback_days         INT           NOT NULL DEFAULT 30,
  winback_template     VARCHAR(480)  NULL,
  promo_weekly_cap     INT           NOT NULL DEFAULT 1,
  created_at           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_business_sms_hub_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);
