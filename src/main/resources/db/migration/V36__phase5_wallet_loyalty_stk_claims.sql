-- Phase 5 Slices 3–6 scaffolding: wallets, loyalty, STK intents, public claims (PHASE_5_PLAN.md).

CREATE TABLE business_credit_settings (
  business_id              CHAR(36) PRIMARY KEY,
  loyalty_points_per_kes   DECIMAL(14, 8) NOT NULL DEFAULT 0,
  loyalty_kes_per_point    DECIMAL(14, 8) NOT NULL DEFAULT 0.01,
  loyalty_max_redeem_bps   INT            NOT NULL DEFAULT 5000,
  CONSTRAINT fk_bcs_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE wallet_transactions (
  id                 CHAR(36) PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  credit_account_id  CHAR(36)      NOT NULL,
  sale_id            CHAR(36)      NULL,
  txn_type           VARCHAR(24)   NOT NULL,
  amount             DECIMAL(14, 2) NOT NULL,
  created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_wt_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_wt_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  CONSTRAINT fk_wt_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
);

CREATE INDEX idx_wt_account_time ON wallet_transactions (credit_account_id, created_at);

CREATE TABLE loyalty_transactions (
  id                CHAR(36) PRIMARY KEY,
  business_id       CHAR(36)      NOT NULL,
  credit_account_id CHAR(36)      NOT NULL,
  sale_id           CHAR(36)      NULL,
  txn_type          VARCHAR(24)   NOT NULL,
  points            INT           NOT NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_lt_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_lt_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  CONSTRAINT fk_lt_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
);

CREATE INDEX idx_lt_account_time ON loyalty_transactions (credit_account_id, created_at);

ALTER TABLE sale_payments
  ADD COLUMN gateway_txn_id VARCHAR(128) NULL;

ALTER TABLE credit_accounts
  ADD CONSTRAINT chk_credit_wallet_balance_nonneg CHECK (wallet_balance >= 0);

ALTER TABLE credit_accounts
  ADD CONSTRAINT chk_loyalty_points_nonneg CHECK (loyalty_points >= 0);

CREATE TABLE public_payment_claims (
  id                   CHAR(36) PRIMARY KEY,
  business_id          CHAR(36) NOT NULL,
  credit_account_id    CHAR(36) NOT NULL,
  token_hash           CHAR(64) NOT NULL,
  status               VARCHAR(24) NOT NULL DEFAULT 'issued',
  submitted_amount     DECIMAL(14, 2) NULL,
  submitted_reference  VARCHAR(128) NULL,
  credit_note          VARCHAR(500) NULL,
  approved_journal_id  CHAR(36) NULL,
  rejection_reason     VARCHAR(500) NULL,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ppc_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_ppc_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  UNIQUE KEY uq_ppc_token_hash (token_hash)
);

CREATE INDEX idx_ppc_account_status ON public_payment_claims (credit_account_id, status);

CREATE TABLE mpesa_stk_intents (
  id                          CHAR(36) PRIMARY KEY,
  business_id                 CHAR(36) NOT NULL,
  credit_account_id           CHAR(36) NOT NULL,
  sale_id                     CHAR(36) NULL,
  amount                      DECIMAL(14, 2) NOT NULL,
  idempotency_key             VARCHAR(128) NOT NULL,
  checkout_request_id         VARCHAR(128) NULL,
  status                      VARCHAR(24) NOT NULL DEFAULT 'pending',
  gateway_confirmation_code VARCHAR(128) NULL,
  fulfilled_wallet_txn_id     CHAR(36) NULL,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_stk_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_stk_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  CONSTRAINT fk_stk_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
  UNIQUE KEY uq_stk_business_idem (business_id, idempotency_key)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('8f6c3b3a-1b6f-4c3a-9f0a-0c2b6b0f1a73', 'credits.wallet.write',
   'Top up customer prepaid wallet at the counter.'),
  ('9c0b6a42-0b4c-4f9e-8e8a-7a8e6dd55e74', 'credits.claims.review',
   'Review and approve public customer payment claims.'),
  ('a6d86c5c-2ef6-4d2f-bc3c-2c0b2c1b8e75', 'credits.claims.issue',
   'Generate public payment claim links for customers.'),
  ('b2c0a4fd-7f2f-4dbb-9b9b-9d6f4b02f176', 'payments.stk.initiate',
   'Initiate M-Pesa STK push for wallet top-up or payment.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '8f6c3b3a-1b6f-4c3a-9f0a-0c2b6b0f1a73'),
  ('22222222-0000-0000-0000-000000000001', '9c0b6a42-0b4c-4f9e-8e8a-7a8e6dd55e74'),
  ('22222222-0000-0000-0000-000000000001', 'a6d86c5c-2ef6-4d2f-bc3c-2c0b2c1b8e75'),
  ('22222222-0000-0000-0000-000000000001', 'b2c0a4fd-7f2f-4dbb-9b9b-9d6f4b02f176'),
  ('22222222-0000-0000-0000-000000000002', '8f6c3b3a-1b6f-4c3a-9f0a-0c2b6b0f1a73'),
  ('22222222-0000-0000-0000-000000000002', '9c0b6a42-0b4c-4f9e-8e8a-7a8e6dd55e74'),
  ('22222222-0000-0000-0000-000000000002', 'a6d86c5c-2ef6-4d2f-bc3c-2c0b2c1b8e75'),
  ('22222222-0000-0000-0000-000000000002', 'b2c0a4fd-7f2f-4dbb-9b9b-9d6f4b02f176'),
  ('22222222-0000-0000-0000-000000000003', '8f6c3b3a-1b6f-4c3a-9f0a-0c2b6b0f1a73'),
  ('22222222-0000-0000-0000-000000000003', 'a6d86c5c-2ef6-4d2f-bc3c-2c0b2c1b8e75'),
  ('22222222-0000-0000-0000-000000000003', 'b2c0a4fd-7f2f-4dbb-9b9b-9d6f4b02f176'),
  ('22222222-0000-0000-0000-000000000004', '8f6c3b3a-1b6f-4c3a-9f0a-0c2b6b0f1a73'),
  ('22222222-0000-0000-0000-000000000004', 'a6d86c5c-2ef6-4d2f-bc3c-2c0b2c1b8e75'),
  ('22222222-0000-0000-0000-000000000004', 'b2c0a4fd-7f2f-4dbb-9b9b-9d6f4b02f176');