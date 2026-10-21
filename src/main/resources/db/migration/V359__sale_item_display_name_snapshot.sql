-- Freeze the customer-facing product title on each sale line.
--
-- Receipts and sales history used to re-read items.name at read time, so a
-- catalog rename/detach after checkout made an old receipt and the sales list
-- disagree (e.g. "CACTUS CT-PEARL/WHITE 10MM" vs "CT-PEARL/WHITE 10MM").
-- New sales store the canonical ProductDisplayName here; readers prefer the
-- snapshot and fall back to the live catalog for rows written before this
-- migration (left NULL on purpose so the fallback can compose the old title).
ALTER TABLE sale_items
  ADD COLUMN display_name VARCHAR(500) NULL AFTER line_label;
