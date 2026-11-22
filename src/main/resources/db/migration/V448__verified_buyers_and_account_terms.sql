-- P3-3: an order placed on the marketplace by a signed-in buyer names the shop it is for.
-- P3-4: an account can belong to a shop (not only a phone number) and carry a trade discount;
--       an order that takes the buyer over the account's limit is marked.

ALTER TABLE sales_orders
  ADD COLUMN buyer_business_id VARCHAR(36) NULL,
  ADD COLUMN over_credit_limit TINYINT(1) NOT NULL DEFAULT 0;

ALTER TABLE trade_accounts
  ADD COLUMN buyer_business_id VARCHAR(36) NULL,
  ADD COLUMN discount_percent DECIMAL(5,2) NULL;

CREATE UNIQUE INDEX uq_trade_accounts_buyer_business ON trade_accounts (business_id, buyer_business_id);
