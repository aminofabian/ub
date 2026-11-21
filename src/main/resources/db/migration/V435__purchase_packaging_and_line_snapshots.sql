-- Backend-owned purchase packaging (rework of the package-purchase flow).
--
-- 1. item_pack_options gains the optional second packaging level
--    (outer unit -> inner pack -> base item) and a per-item default purchase unit.
--    units_per_pack stays the authoritative base-unit count of one purchase unit, so
--    all stock math (receive, sell, transfer) keeps a single conversion point.
-- 2. raw_purchase_lines keeps the packaging the buyer actually entered, so reopening a
--    draft shows "5 cartons of 24" instead of the expanded 120 pieces.
-- 3. supplier_invoice_lines keeps an immutable snapshot of that packaging at post time.
--    Editing or deleting a pack option never rewrites this history.

ALTER TABLE item_pack_options
  ADD COLUMN inner_pack_unit VARCHAR(32) NULL AFTER pack_unit,
  ADD COLUMN inner_pack_count DECIMAL(14, 4) NULL AFTER units_per_pack,
  ADD COLUMN base_units_per_inner DECIMAL(14, 4) NULL AFTER inner_pack_count,
  ADD COLUMN is_default_purchase BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

CREATE INDEX idx_item_pack_options_default
  ON item_pack_options (business_id, item_id, is_default_purchase);

ALTER TABLE raw_purchase_lines
  ADD COLUMN purchase_unit VARCHAR(32) NULL AFTER pack_option_id,
  ADD COLUMN purchase_qty DECIMAL(14, 4) NULL AFTER purchase_unit,
  ADD COLUMN units_per_purchase_unit DECIMAL(14, 4) NULL AFTER purchase_qty,
  ADD COLUMN pack_price DECIMAL(14, 4) NULL AFTER units_per_purchase_unit;

ALTER TABLE supplier_invoice_lines
  ADD COLUMN pack_option_id CHAR(36) NULL AFTER raw_line_id,
  ADD COLUMN purchase_unit VARCHAR(32) NULL AFTER pack_option_id,
  ADD COLUMN purchase_qty DECIMAL(14, 4) NULL AFTER purchase_unit,
  ADD COLUMN units_per_purchase_unit DECIMAL(14, 4) NULL AFTER purchase_qty,
  ADD COLUMN pack_price DECIMAL(14, 4) NULL AFTER units_per_purchase_unit,
  ADD COLUMN base_unit VARCHAR(16) NULL AFTER pack_price;

-- Existing drafts and invoices keep NULL packaging columns: their expanded quantities are
-- already the source of truth, and inventing a pack shape for them would rewrite history.
