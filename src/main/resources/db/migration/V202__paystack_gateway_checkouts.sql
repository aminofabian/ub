-- Provider-hosted checkout tracking (Paystack v1: initialize → authorization_url).
-- Mirrors gateway_stk_pushes; `reference` is the webhook routing key (see
-- docs/PAYMENT_INFRASTRUCTURE_PAYSTACK_SCOPE.md §4.6) and must resolve to this
-- row WITHOUT decrypting any tenant credentials.

CREATE TABLE gateway_checkouts (
  id                      CHAR(36) PRIMARY KEY,
  business_id             CHAR(36) NOT NULL,
  gateway_type            VARCHAR(32) NOT NULL,
  config_id               CHAR(36) NULL,
  reference               VARCHAR(128) NOT NULL,
  context_type            VARCHAR(32) NOT NULL,
  context_id              CHAR(36) NULL,
  amount                  DECIMAL(14, 2) NOT NULL,
  currency                VARCHAR(8) NOT NULL DEFAULT 'KES',
  customer_email          VARCHAR(255) NULL,
  status                  VARCHAR(24) NOT NULL DEFAULT 'pending',
  provider_transaction_id VARCHAR(64) NULL,
  access_code             VARCHAR(64) NULL,
  authorization_url       VARCHAR(512) NULL,
  failure_reason          VARCHAR(512) NULL,
  metadata_json           TEXT NULL,
  confirmed_at            TIMESTAMP NULL,
  last_verified_at        TIMESTAMP NULL,
  verify_count            INT NOT NULL DEFAULT 0,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_gc_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_gc_reference (reference),
  KEY idx_gc_pending (business_id, status, created_at),
  KEY idx_gc_context (context_type, context_id)
);
