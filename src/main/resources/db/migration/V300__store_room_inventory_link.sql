-- Store room ↔ inventory connection.
--
-- The store room used to be a manual register with no link to the catalogue. A
-- merchant now picks one of two modes the first time they open /store:
--
--   * 'standalone' — manual back-room register (the previous behaviour)
--   * 'connected'  — rows mirror catalogue products and counts follow inventory,
--                    so they move on their own as products sell
--
-- `mode` stays NULL until that choice is made, which is what makes the first-run
-- prompt possible.
--
-- `store_items.item_id` links a row to a catalogue item. Existing rows keep a
-- NULL link until the merchant connects; we then auto-link by barcode.
--
-- Note: no `branch_id` here on purpose. `items.current_stock` is the
-- business-wide on-hand figure maintained by every sale path (POS checkout, web
-- order, void, refund), and the store room — unlike batch-level stock — has
-- never been branch-scoped.

CREATE TABLE store_room_settings (
  business_id  CHAR(36)    NOT NULL PRIMARY KEY,
  mode         VARCHAR(16) NULL,
  connected_at TIMESTAMP   NULL,
  updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_store_room_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

ALTER TABLE store_items
  ADD COLUMN item_id CHAR(36) NULL AFTER barcode,
  ADD INDEX idx_store_items_item (business_id, item_id),
  ADD CONSTRAINT fk_store_items_item
      FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE SET NULL;
