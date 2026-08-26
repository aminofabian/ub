-- B3 (docs/MULTI_PACK_OPTIONS_SCOPE.md §6, §8):
-- 1. Path B receive lines carry the selected saved pack option (server-authoritative expansion).
-- 2. Backfill legacy single-pack scalars into first-class item pack options (no data loss).

ALTER TABLE raw_purchase_lines
  ADD COLUMN pack_option_id CHAR(36) NULL AFTER draft_expiry_date;

CREATE INDEX idx_raw_purchase_lines_pack_option ON raw_purchase_lines (pack_option_id);

ALTER TABLE raw_purchase_lines
  ADD CONSTRAINT fk_rpl_pack_option FOREIGN KEY (pack_option_id) REFERENCES item_pack_options(id);

-- Backfill one active pack option per legacy single-pack link where pack_size > 1.
-- Deduped by (business, item, units, unit) so multiple links sharing a shape create one row.
INSERT INTO item_pack_options (
  id, business_id, item_id, label, pack_unit, units_per_pack, default_pack_price,
  barcode, sku_suffix, sort_order, active, version, created_at, updated_at
)
SELECT UUID(), i.business_id, i.id, NULL,
       COALESCE(NULLIF(TRIM(sp.pack_unit), ''), 'pack'),
       sp.pack_size, NULL, NULL, NULL, 0, TRUE, 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM supplier_products sp
JOIN items i ON i.id = sp.item_id AND i.deleted_at IS NULL
WHERE sp.deleted_at IS NULL
  AND sp.pack_size IS NOT NULL
  AND sp.pack_size > 1
  AND NOT EXISTS (
    SELECT 1 FROM item_pack_options op
    WHERE op.business_id = i.business_id
      AND op.item_id = i.id
      AND op.units_per_pack = sp.pack_size
      AND op.pack_unit = COALESCE(NULLIF(TRIM(sp.pack_unit), ''), 'pack')
  );
