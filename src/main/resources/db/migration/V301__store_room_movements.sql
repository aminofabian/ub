-- Store-room movement log — the back-room ops trail.
--
-- Why a dedicated table instead of stock_movements:
--   * A standalone store-room row has no `item_id` and the business-wide store
--     room has no `branch_id`; stock_movements requires BOTH (NOT NULL). Standalone
--     take-outs therefore cannot be expressed there at all.
--   * For linked products the inventory ledger stays the stock of record; this
--     table carries the *why / who / when* and points back at what the ledger did.
--
-- See docs/scopes/STORE_ROOM_MANAGEMENT_SCOPE.md §6.

CREATE TABLE store_room_movements (
  id              CHAR(36)      PRIMARY KEY,
  business_id     CHAR(36)      NOT NULL,
  -- Nulled rather than cascaded: deleting a register row must not erase history.
  store_item_id   CHAR(36)      NULL,
  item_id         CHAR(36)      NULL,
  direction       VARCHAR(8)    NOT NULL,   -- IN | OUT
  reason          VARCHAR(32)   NOT NULL,   -- StoreRoomReason enum name
  stock_effect    VARCHAR(16)   NOT NULL,   -- NONE | DECREASE | INCREASE ('INCREASE' reserved)
  quantity        DECIMAL(14,4) NOT NULL,   -- inventory scale, even though store_items.quantity is INT
  note            VARCHAR(255)  NULL,
  -- What the ledger actually did, so the log can be reconciled against it.
  movement_id     CHAR(36)      NULL,       -- stock_movements.id (first, when a wastage split produced many)
  movement_count  INT           NOT NULL DEFAULT 0,
  branch_id       CHAR(36)      NULL,       -- branch the ledger write used
  created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by      CHAR(36)      NULL,
  CONSTRAINT fk_srm_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_srm_store_item FOREIGN KEY (store_item_id) REFERENCES store_items (id) ON DELETE SET NULL,
  CONSTRAINT fk_srm_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE SET NULL,
  CONSTRAINT fk_srm_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

CREATE INDEX idx_srm_business_created ON store_room_movements (business_id, created_at);
CREATE INDEX idx_srm_item_created ON store_room_movements (item_id, created_at);
