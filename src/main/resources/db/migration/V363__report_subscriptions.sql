-- Report delivery subscriptions: recurring WhatsApp/SMS/email delivery of a report
-- summary to the shop owner. One row per schedule. Gated by the reports.export
-- permission (no new permission). last_sent_period dedupes a period across ticks/instances.

CREATE TABLE report_subscriptions (
  id                CHAR(36)      NOT NULL PRIMARY KEY,
  business_id       CHAR(36)      NOT NULL,
  report_key        VARCHAR(64)   NOT NULL,
  format            VARCHAR(16)   NOT NULL DEFAULT 'pdf',
  frequency         VARCHAR(16)   NOT NULL,
  send_time         VARCHAR(5)    NOT NULL DEFAULT '07:00',
  day_of_week       INT           NULL,
  day_of_month      INT           NULL,
  channels          VARCHAR(64)   NOT NULL,
  recipient_email   VARCHAR(255)  NULL,
  branch_id         CHAR(36)      NULL,
  category_id       CHAR(36)      NULL,
  active            BOOLEAN       NOT NULL DEFAULT TRUE,
  created_by        CHAR(36)      NOT NULL,
  last_sent_period  VARCHAR(16)   NULL,
  created_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_report_subs_business ON report_subscriptions (business_id);
CREATE INDEX idx_report_subs_active ON report_subscriptions (active);
