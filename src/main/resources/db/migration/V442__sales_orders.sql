-- ADR 0040: an order a trade buyer places with a supplier's shop. It lives in the supplier's
-- tenant (business_id). Placing it takes no stock and it does not expire; the supplier answers it.

CREATE TABLE sales_orders (
  id                   VARCHAR(36)   NOT NULL,
  business_id          VARCHAR(36)   NOT NULL,
  branch_id            VARCHAR(36)   NOT NULL,
  status               VARCHAR(24)   NOT NULL COMMENT 'placed, accepted, part_accepted, rejected, dispatched, cancelled',
  source               VARCHAR(24)   NOT NULL COMMENT 'storefront',
  currency             VARCHAR(8)    NOT NULL,
  total                DECIMAL(14,2) NOT NULL,
  buyer_name           VARCHAR(255)  NOT NULL,
  buyer_phone          VARCHAR(64)   NOT NULL,
  buyer_email          VARCHAR(255)  NULL,
  buyer_business_name  VARCHAR(255)  NULL,
  notes                VARCHAR(2000) NULL,
  version              BIGINT        NOT NULL DEFAULT 0,
  created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_sales_orders_business (business_id, status, created_at),
  KEY idx_sales_orders_buyer (business_id, buyer_phone, status)
);

CREATE TABLE sales_order_lines (
  id            VARCHAR(36)   NOT NULL,
  order_id      VARCHAR(36)   NOT NULL,
  business_id   VARCHAR(36)   NOT NULL,
  item_id       VARCHAR(36)   NOT NULL,
  item_name     VARCHAR(500)  NOT NULL,
  quantity      DECIMAL(14,4) NOT NULL,
  unit_price    DECIMAL(14,4) NOT NULL,
  line_total    DECIMAL(14,2) NOT NULL,
  line_index    INT           NOT NULL,
  status        VARCHAR(24)   NOT NULL COMMENT 'pending, accepted, rejected',
  accepted_qty  DECIMAL(14,4) NULL,
  PRIMARY KEY (id),
  KEY idx_sales_order_lines_order (order_id, line_index),
  CONSTRAINT fk_sales_order_lines_order FOREIGN KEY (order_id) REFERENCES sales_orders (id)
);
