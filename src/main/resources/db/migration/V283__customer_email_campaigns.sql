-- Tenant CRM email campaigns (shop staff → customers). Distinct from platform_email_campaigns.

CREATE TABLE customer_email_campaigns (
  id                          CHAR(36)      PRIMARY KEY,
  business_id                 CHAR(36)      NOT NULL,
  name                        VARCHAR(255)  NOT NULL,
  subject                     VARCHAR(255)  NOT NULL,
  body_html                   TEXT          NOT NULL,
  recipient_method            VARCHAR(32)   NOT NULL,
  filter_json                 TEXT          NULL,
  status                      VARCHAR(16)   NOT NULL,
  recipients_targeted         INTEGER       NOT NULL DEFAULT 0,
  recipients_sent             INTEGER       NOT NULL DEFAULT 0,
  recipients_failed           INTEGER       NOT NULL DEFAULT 0,
  recipients_skipped          INTEGER       NOT NULL DEFAULT 0,
  created_by_user_id          CHAR(36)      NULL,
  created_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at                  TIMESTAMP     NULL,
  completed_at                TIMESTAMP     NULL,
  CONSTRAINT chk_customer_email_campaigns_status
    CHECK (status IN ('DRAFT', 'RUNNING', 'COMPLETED', 'FAILED')),
  CONSTRAINT chk_customer_email_campaigns_method
    CHECK (recipient_method IN ('specific', 'filtered', 'all_eligible')),
  CONSTRAINT fk_customer_email_campaigns_business
    FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_customer_email_campaigns_business_created
  ON customer_email_campaigns (business_id, created_at DESC);

CREATE TABLE customer_email_campaign_recipients (
  id              CHAR(36)      PRIMARY KEY,
  campaign_id     CHAR(36)      NOT NULL,
  business_id     CHAR(36)      NOT NULL,
  customer_id     CHAR(36)      NOT NULL,
  email           VARCHAR(255)  NOT NULL,
  customer_name   VARCHAR(500)  NULL,
  status          VARCHAR(16)   NOT NULL,
  skip_reason     VARCHAR(64)   NULL,
  error           VARCHAR(1000) NULL,
  sent_at         TIMESTAMP     NULL,
  CONSTRAINT chk_customer_email_recipients_status
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
  CONSTRAINT fk_customer_email_recipients_campaign
    FOREIGN KEY (campaign_id) REFERENCES customer_email_campaigns (id),
  CONSTRAINT fk_customer_email_recipients_business
    FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_customer_email_recipients_campaign
  ON customer_email_campaign_recipients (campaign_id, status);
