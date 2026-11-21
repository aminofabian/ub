-- Path A purchase-order packaging (ADR 0036 follow-up).
--
-- purchase_order_lines keeps qty_ordered / unit_estimated_cost in base stock units, unchanged:
-- every existing consumer (remaining = ordered - received, GRN, three-way match) keeps working.
-- The columns below record how the buyer ordered it, so a purchase order can say
-- "2 cartons × 24 @ 1,200" while the stock arithmetic stays in base units.

ALTER TABLE purchase_order_lines
  ADD COLUMN pack_option_id CHAR(36) NULL AFTER unit_estimated_cost,
  ADD COLUMN purchase_unit VARCHAR(32) NULL AFTER pack_option_id,
  ADD COLUMN purchase_qty DECIMAL(14, 4) NULL AFTER purchase_unit,
  ADD COLUMN units_per_purchase_unit DECIMAL(14, 4) NULL AFTER purchase_qty,
  ADD COLUMN pack_price DECIMAL(14, 4) NULL AFTER units_per_purchase_unit,
  ADD COLUMN base_unit VARCHAR(16) NULL AFTER pack_price;

CREATE INDEX idx_purchase_order_lines_pack_option
  ON purchase_order_lines (pack_option_id);

-- Legacy lines keep NULL packaging: their ordered quantity was already the base quantity.
