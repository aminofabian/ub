-- Purchase delivery phase now includes partially_delivered (19 chars),
-- which exceeds the original VARCHAR(16) width.

ALTER TABLE purchase_orders
  MODIFY COLUMN delivery_status VARCHAR(32) NOT NULL DEFAULT 'not_shipped';