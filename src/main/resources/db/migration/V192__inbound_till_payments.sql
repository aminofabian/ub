-- Persist unmatched KopoKopo buygoods (till) webhooks for late-bind to till-awaits, sales, or claims.

CREATE TABLE inbound_till_payments (
  id                    CHAR(36) PRIMARY KEY,
  business_id           CHAR(36) NOT NULL,
  gateway_type          VARCHAR(32) NOT NULL,
  gateway_event_id      VARCHAR(128) NOT NULL,
  mpesa_receipt         VARCHAR(128) NULL,
  phone                 VARCHAR(32) NULL,
  amount                DECIMAL(14, 2) NOT NULL,
  till_number           VARCHAR(64) NULL,
  raw_payload           MEDIUMTEXT NULL,
  status                VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  linked_sale_id        CHAR(36) NULL,
  linked_push_id        CHAR(36) NULL,
  linked_claim_id       CHAR(36) NULL,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_itp_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  UNIQUE KEY uq_itp_gateway_event (gateway_type, gateway_event_id),
  UNIQUE KEY uq_itp_business_receipt (business_id, mpesa_receipt),
  KEY idx_itp_pending (business_id, status, created_at),
  KEY idx_itp_receipt (business_id, mpesa_receipt, status)
);
