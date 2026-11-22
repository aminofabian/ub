-- P4 (trade depth).
-- A price for one product for one buyer's account. It replaces the shop's price for that buyer.
CREATE TABLE trade_account_prices (
  id                VARCHAR(36)   NOT NULL,
  business_id       VARCHAR(36)   NOT NULL,
  trade_account_id  VARCHAR(36)   NOT NULL,
  item_id           VARCHAR(36)   NOT NULL,
  unit_price        DECIMAL(14,2) NOT NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trade_account_prices (trade_account_id, item_id),
  KEY idx_trade_account_prices_business (business_id, trade_account_id)
);

-- An order a buying shop asked to be placed again every so many days.
CREATE TABLE standing_orders (
  id                    VARCHAR(36)   NOT NULL,
  supplier_business_id  VARCHAR(36)   NOT NULL,
  buyer_business_id     VARCHAR(36)   NOT NULL,
  buyer_business_name   VARCHAR(255)  NOT NULL,
  buyer_name            VARCHAR(255)  NOT NULL,
  buyer_phone           VARCHAR(64)   NOT NULL,
  every_days            INT           NOT NULL,
  next_run_on           DATE          NOT NULL,
  active                TINYINT(1)    NOT NULL DEFAULT 1,
  last_order_id         VARCHAR(36)   NULL,
  last_result           VARCHAR(500)  NULL,
  created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_standing_orders_due (active, next_run_on),
  KEY idx_standing_orders_supplier (supplier_business_id, active),
  KEY idx_standing_orders_buyer (buyer_business_id, active)
);

CREATE TABLE standing_order_lines (
  id                 VARCHAR(36)   NOT NULL,
  standing_order_id  VARCHAR(36)   NOT NULL,
  item_id            VARCHAR(36)   NOT NULL,
  item_name          VARCHAR(500)  NOT NULL,
  quantity           DECIMAL(14,4) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_standing_order_lines_order (standing_order_id),
  CONSTRAINT fk_standing_order_lines_order FOREIGN KEY (standing_order_id) REFERENCES standing_orders (id)
);
