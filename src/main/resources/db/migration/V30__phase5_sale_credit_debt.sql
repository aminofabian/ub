-- Phase 5 Slice 2 — sales.customer_id, tab tender, credit_transactions (PHASE_5_PLAN.md §Slice 2).

ALTER TABLE sales
  ADD COLUMN customer_id CHAR(36) NULL,
  ADD CONSTRAINT fk_sales_customer FOREIGN KEY (customer_id) REFERENCES customers (id);

CREATE INDEX idx_sales_business_customer ON sales (business_id, customer_id);

CREATE TABLE credit_transactions (
  id CHAR(36) PRIMARY KEY,
  business_id CHAR(36) NOT NULL,
  credit_account_id CHAR(36) NOT NULL,
  sale_id CHAR(36) NULL,
  txn_type VARCHAR(24) NOT NULL,
  amount DECIMAL(14, 2) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_credit_txn_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_credit_txn_account FOREIGN KEY (credit_account_id) REFERENCES credit_accounts (id),
  CONSTRAINT fk_credit_txn_sale FOREIGN KEY (sale_id) REFERENCES sales (id)
);

CREATE INDEX idx_credit_txn_account_time ON credit_transactions (credit_account_id, created_at);
