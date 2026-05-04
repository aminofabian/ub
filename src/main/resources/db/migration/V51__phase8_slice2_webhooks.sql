-- Phase 8 Slice 2 — outbound webhooks (tenant subscriptions + transactional outbox deliveries).

INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000044', 'integrations.webhooks.manage',
   'Create and manage webhook subscriptions for outbound integrations.');

INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000044');

CREATE TABLE webhook_subscriptions (
  id              CHAR(36) PRIMARY KEY,
  business_id     CHAR(36) NOT NULL,
  label           VARCHAR(255) NOT NULL,
  target_url      VARCHAR(2048) NOT NULL,
  signing_secret  VARCHAR(128) NOT NULL,
  events          JSON NOT NULL,
  active          BOOLEAN NOT NULL DEFAULT TRUE,
  failure_count   INT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_webhook_subscriptions_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_webhook_subscriptions_business ON webhook_subscriptions (business_id);

CREATE TABLE webhook_deliveries (
  id                CHAR(36) PRIMARY KEY,
  business_id       CHAR(36) NOT NULL,
  subscription_id   CHAR(36) NOT NULL,
  event_type        VARCHAR(128) NOT NULL,
  payload_json      JSON NOT NULL,
  status            VARCHAR(32) NOT NULL,
  attempt_count     INT NOT NULL DEFAULT 0,
  next_attempt_at   TIMESTAMP NULL,
  last_http_status  INT NULL,
  last_error        VARCHAR(2000) NULL,
  idempotency_key   VARCHAR(255) NULL,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at           TIMESTAMP NULL,
  CONSTRAINT fk_webhook_deliveries_business     FOREIGN KEY (business_id)       REFERENCES businesses(id),
  CONSTRAINT fk_webhook_deliveries_subscription FOREIGN KEY (subscription_id) REFERENCES webhook_subscriptions(id)
);

CREATE UNIQUE INDEX uq_webhook_deliveries_sub_idempotency ON webhook_deliveries (subscription_id, idempotency_key);
CREATE INDEX idx_webhook_deliveries_pending ON webhook_deliveries (status, next_attempt_at, created_at);
