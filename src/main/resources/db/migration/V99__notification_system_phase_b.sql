-- Phase B — outbox, delivery tracking, stock-low batching.

CREATE TABLE notification_events (
  id              CHAR(36)       PRIMARY KEY,
  business_id     CHAR(36)       NOT NULL,
  event_type      VARCHAR(64)    NOT NULL,
  aggregate_type  VARCHAR(32)    NOT NULL,
  aggregate_id    CHAR(36)       NOT NULL,
  payload_json    JSON           NOT NULL,
  dedupe_key      VARCHAR(191)   NOT NULL,
  status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
  attempt_count   INT            NOT NULL DEFAULT 0,
  last_error      VARCHAR(500)   NULL,
  processed_at    TIMESTAMP(3)   NULL,
  created_at      TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_notification_events_dedupe (business_id, dedupe_key),
  INDEX idx_notification_events_pending (status, created_at)
);

CREATE TABLE notification_deliveries (
  id                  CHAR(36)       PRIMARY KEY,
  notification_id     CHAR(36)       NOT NULL,
  business_id         CHAR(36)       NOT NULL,
  channel             VARCHAR(16)    NOT NULL,
  status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
  provider            VARCHAR(32)    NULL,
  provider_message_id VARCHAR(128)   NULL,
  attempt_count       INT            NOT NULL DEFAULT 0,
  next_retry_at       TIMESTAMP(3)   NULL,
  last_error          VARCHAR(500)   NULL,
  sent_at             TIMESTAMP(3)   NULL,
  created_at          TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_notification_deliveries_retry (status, next_retry_at),
  INDEX idx_notification_deliveries_notification (notification_id)
);

CREATE TABLE notification_batches (
  id            CHAR(36)       PRIMARY KEY,
  business_id   CHAR(36)       NOT NULL,
  batch_key     VARCHAR(191)   NOT NULL,
  window_start  TIMESTAMP(3)   NOT NULL,
  window_end    TIMESTAMP(3)   NOT NULL,
  payload_json  JSON           NOT NULL,
  flushed_at    TIMESTAMP(3)   NULL,
  created_at    TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_notification_batches_open (business_id, batch_key, window_end)
);

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000006', NULL, 'stock.low', 'en', 1,
   'Low stock alert', '{{itemCount}} items at or below reorder: {{itemNames}}.',
   '/business/inventory', 'OPERATIONAL', 'inventory', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000007', NULL, 'sales.daily_digest', 'en', 1,
   'Daily sales summary', '{{businessDay}}: {{revenue}} {{currency}} revenue ({{orderCount}} days of record).',
   '/business/reports', 'OPERATIONAL', 'sales', '["IN_APP"]', TRUE);
