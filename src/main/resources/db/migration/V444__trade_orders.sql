-- ADR 0041: the one record two shops share about an order. It belongs to neither shop's books.
-- It carries products, packs, quantities and prices; no names of people, no phone numbers.

CREATE TABLE trade_orders (
  id                        VARCHAR(36)   NOT NULL,
  supplier_business_id      VARCHAR(36)   NOT NULL,
  supplier_marketplace_id   VARCHAR(36)   NOT NULL,
  buyer_business_id         VARCHAR(36)   NOT NULL,
  sales_order_id            VARCHAR(36)   NOT NULL,
  purchase_order_id         VARCHAR(36)   NULL,
  order_code                VARCHAR(24)   NOT NULL,
  supplier_name             VARCHAR(255)  NOT NULL,
  order_state               VARCHAR(16)   NOT NULL COMMENT 'placed, confirmed, cancelled, disputed',
  delivery_state            VARCHAR(16)   NOT NULL COMMENT 'none, part, all',
  money_state               VARCHAR(16)   NOT NULL COMMENT 'unbilled, invoiced, part_paid, settled',
  currency                  VARCHAR(8)    NOT NULL,
  total                     DECIMAL(14,2) NOT NULL,
  next_event_no             INT           NOT NULL DEFAULT 1,
  version                   BIGINT        NOT NULL DEFAULT 0,
  created_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trade_orders_sales_order (sales_order_id),
  KEY idx_trade_orders_buyer (buyer_business_id, order_state, created_at),
  KEY idx_trade_orders_supplier (supplier_business_id, order_state, created_at)
);

CREATE TABLE trade_order_lines (
  id              VARCHAR(36)   NOT NULL,
  trade_order_id  VARCHAR(36)   NOT NULL,
  line_no         INT           NOT NULL,
  product_name    VARCHAR(500)  NOT NULL,
  barcode         VARCHAR(191)  NULL,
  pack_unit       VARCHAR(255)  NULL,
  pack_size       DECIMAL(14,4) NULL,
  quantity        DECIMAL(14,4) NOT NULL,
  unit_price      DECIMAL(14,4) NOT NULL,
  line_total      DECIMAL(14,2) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_trade_order_lines_order (trade_order_id, line_no),
  CONSTRAINT fk_trade_order_lines_order FOREIGN KEY (trade_order_id) REFERENCES trade_orders (id)
);

CREATE TABLE trade_order_events (
  id              VARCHAR(36)   NOT NULL,
  trade_order_id  VARCHAR(36)   NOT NULL,
  event_no        INT           NOT NULL,
  kind            VARCHAR(32)   NOT NULL COMMENT 'sent, confirmed, declined, dispatched, cancelled',
  actor_side      VARCHAR(16)   NOT NULL COMMENT 'supplier, buyer',
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trade_order_events_no (trade_order_id, event_no),
  CONSTRAINT fk_trade_order_events_order FOREIGN KEY (trade_order_id) REFERENCES trade_orders (id)
);
