-- STK push tracking, webhook idempotency, web order payment fields

CREATE TABLE payment_webhook_events (
  id                    CHAR(36) PRIMARY KEY,
  business_id           CHAR(36) NOT NULL,
  gateway_type          VARCHAR(32) NOT NULL,
  gateway_event_id      VARCHAR(128) NOT NULL,
  topic                 VARCHAR(128) NULL,
  processed_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  raw_payload           MEDIUMTEXT NULL,
  CONSTRAINT fk_pwe_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_pwe_gateway_event (gateway_type, gateway_event_id)
);

CREATE INDEX idx_pwe_business_processed ON payment_webhook_events (business_id, processed_at);

CREATE TABLE gateway_stk_pushes (
  id                      CHAR(36) PRIMARY KEY,
  business_id             CHAR(36) NOT NULL,
  gateway_type            VARCHAR(32) NOT NULL,
  config_id               CHAR(36) NULL,
  gateway_checkout_id     VARCHAR(128) NOT NULL,
  merchant_reference      VARCHAR(128) NOT NULL,
  context_type            VARCHAR(32) NOT NULL,
  context_id              CHAR(36) NULL,
  amount                  DECIMAL(14, 2) NOT NULL,
  phone_number            VARCHAR(32) NOT NULL,
  status                  VARCHAR(24) NOT NULL DEFAULT 'pending',
  gateway_transaction_id  VARCHAR(128) NULL,
  failure_reason          VARCHAR(512) NULL,
  confirmed_at            TIMESTAMP NULL,
  last_polled_at          TIMESTAMP NULL,
  poll_count              INT NOT NULL DEFAULT 0,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_gsp_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_gsp_checkout (gateway_type, gateway_checkout_id),
  KEY idx_gsp_pending (business_id, status, created_at),
  KEY idx_gsp_reference (business_id, merchant_reference, status)
);

ALTER TABLE web_orders
  ADD COLUMN payment_checkout_id VARCHAR(128) NULL AFTER status,
  ADD COLUMN paid_at TIMESTAMP NULL AFTER payment_checkout_id;
