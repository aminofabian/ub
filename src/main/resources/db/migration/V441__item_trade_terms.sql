-- ADR 0038, P1-5: wholesale terms on a shop product.
-- min_order_qty: the least a shopper may order online. NULL = no minimum. The till ignores it.
-- item_price_breaks: a lower unit price from a set quantity upwards ("12 or more: 95 each").

ALTER TABLE items
  ADD COLUMN min_order_qty DECIMAL(14,4) NULL;

CREATE TABLE item_price_breaks (
  id           VARCHAR(36)   NOT NULL,
  business_id  VARCHAR(36)   NOT NULL,
  item_id      VARCHAR(36)   NOT NULL,
  min_qty      DECIMAL(14,4) NOT NULL,
  unit_price   DECIMAL(14,2) NOT NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_item_price_breaks (business_id, item_id, min_qty)
);
