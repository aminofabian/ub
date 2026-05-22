-- Package/bundle sellable SKUs: sell in packs (e.g. tray of 30 eggs) while stock lives on the parent item.
ALTER TABLE items
  ADD COLUMN is_package_variant BOOLEAN NOT NULL DEFAULT FALSE AFTER is_stocked;

CREATE INDEX idx_items_package_variant ON items (business_id, is_package_variant, variant_of_item_id);
