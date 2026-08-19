-- Super-admin outbound email campaigns (platform merchants, not shopper push).

CREATE TABLE platform_email_campaigns (
  id                          CHAR(36)      PRIMARY KEY,
  name                        VARCHAR(255)  NOT NULL,
  segment_key                 VARCHAR(32)   NOT NULL,
  subject                     VARCHAR(255)  NOT NULL,
  body_markdown               TEXT          NOT NULL,
  cta_label                   VARCHAR(120)  NOT NULL DEFAULT 'Continue setup',
  status                      VARCHAR(16)   NOT NULL,
  recipients_targeted         INTEGER       NOT NULL DEFAULT 0,
  recipients_sent             INTEGER       NOT NULL DEFAULT 0,
  recipients_failed           INTEGER       NOT NULL DEFAULT 0,
  recipients_skipped          INTEGER       NOT NULL DEFAULT 0,
  created_by_super_admin_id   CHAR(36)      NULL,
  created_at                  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  started_at                  TIMESTAMP     NULL,
  completed_at                TIMESTAMP     NULL,
  CONSTRAINT chk_platform_email_campaigns_status
    CHECK (status IN ('DRAFT', 'RUNNING', 'COMPLETED', 'FAILED')),
  CONSTRAINT chk_platform_email_campaigns_segment
    CHECK (segment_key IN ('stuck_signup', 'unverified_owners', 'selected_tenants', 'selected_users'))
);

CREATE INDEX idx_platform_email_campaigns_created
  ON platform_email_campaigns (created_at);

CREATE TABLE platform_email_campaign_recipients (
  id              CHAR(36)      PRIMARY KEY,
  campaign_id     CHAR(36)      NOT NULL,
  business_id     CHAR(36)      NOT NULL,
  user_id         CHAR(36)      NOT NULL,
  email           VARCHAR(255)  NOT NULL,
  continue_kind   VARCHAR(16)   NOT NULL,
  status          VARCHAR(16)   NOT NULL,
  error           VARCHAR(1000) NULL,
  sent_at         TIMESTAMP     NULL,
  CONSTRAINT chk_platform_email_recipients_kind
    CHECK (continue_kind IN ('verify', 'hub')),
  CONSTRAINT chk_platform_email_recipients_status
    CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
  CONSTRAINT fk_platform_email_recipients_campaign
    FOREIGN KEY (campaign_id) REFERENCES platform_email_campaigns (id),
  CONSTRAINT fk_platform_email_recipients_business
    FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE INDEX idx_platform_email_recipients_campaign
  ON platform_email_campaign_recipients (campaign_id, status);
