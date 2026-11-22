-- ADR 0038 P1-6: a buyer's account with one supplier's shop. Anyone may order and pay up front;
-- an approved account is what lets the supplier sell to that buyer on credit.
-- phone_key is the phone number in one spelling (2547XXXXXXXX) so the same number matches itself.

CREATE TABLE trade_accounts (
  id             VARCHAR(36)   NOT NULL,
  business_id    VARCHAR(36)   NOT NULL,
  phone_key      VARCHAR(32)   NOT NULL,
  buyer_phone    VARCHAR(64)   NOT NULL,
  buyer_name     VARCHAR(255)  NOT NULL,
  business_name  VARCHAR(255)  NULL,
  note           VARCHAR(1000) NULL,
  status         VARCHAR(16)   NOT NULL COMMENT 'applied, approved, declined, suspended',
  credit_limit   DECIMAL(14,2) NULL,
  terms_days     INT           NULL,
  version        BIGINT        NOT NULL DEFAULT 0,
  created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trade_accounts_phone (business_id, phone_key),
  KEY idx_trade_accounts_status (business_id, status)
);

ALTER TABLE sales_orders
  ADD COLUMN buyer_phone_key VARCHAR(32) NULL,
  ADD COLUMN payment_terms VARCHAR(16) NOT NULL DEFAULT 'prepaid' COMMENT 'prepaid, credit',
  ADD COLUMN trade_account_id VARCHAR(36) NULL;

CREATE INDEX idx_sales_orders_buyer_key ON sales_orders (business_id, buyer_phone_key, status);
