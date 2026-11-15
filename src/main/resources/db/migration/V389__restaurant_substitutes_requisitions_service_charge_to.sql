-- Restaurant operations, the rest of phase 1.5 — ADR 0020, amendment 4.
--
-- 1. A recipe line may name a stand-in ingredient, used when the main one has run out.
--    recipe_lines is small (a few rows per dish), so a nullable column is a quick change.
-- 2. Who a service charge is for: the business (as before) or the staff who serve.
--    restaurant_settings holds one row per business.
-- 3. Kitchen requisitions: the kitchen asks the store for ingredients and the store issues or
--    refuses them. Checked first: store_room_movements already has a kitchen_prep reason, but a
--    movement is one row for one store-room register line, with no request that spans several
--    products and no "asked for 5, given 3"; purchase orders are between the shop and a supplier.
--    So two new tables. Issuing writes no stock movement: a branch has one stock count, and an
--    ingredient leaves it when the dish is sold (V386), so counting it again here would take it twice.
--
-- Forward fix: nothing to undo; an unused column or empty table changes no behaviour.

ALTER TABLE recipe_lines
  ADD COLUMN substitute_item_id CHAR(36) NULL;

ALTER TABLE restaurant_settings
  ADD COLUMN service_charge_to VARCHAR(16) NOT NULL DEFAULT 'BUSINESS';

CREATE TABLE IF NOT EXISTS kitchen_requisitions (
  id             CHAR(36)     NOT NULL PRIMARY KEY,
  business_id    CHAR(36)     NOT NULL,
  branch_id      CHAR(36)     NOT NULL,
  station_id     CHAR(36)     NULL,
  requisition_no BIGINT       NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  note           VARCHAR(255) NULL,
  client_key     VARCHAR(64)  NULL,
  requested_by   CHAR(36)     NOT NULL,
  requested_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_by     CHAR(36)     NULL,
  decided_at     TIMESTAMP    NULL,
  decision_note  VARCHAR(255) NULL,
  KEY idx_kitchen_requisitions_branch (business_id, branch_id, status, requested_at),
  UNIQUE KEY uq_kitchen_requisitions_client (business_id, client_key),
  CONSTRAINT fk_kitchen_requisitions_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

CREATE TABLE IF NOT EXISTS kitchen_requisition_lines (
  id                 CHAR(36)      NOT NULL PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  requisition_id     CHAR(36)      NOT NULL,
  item_id            CHAR(36)      NOT NULL,
  item_name          VARCHAR(255)  NOT NULL,
  unit               VARCHAR(32)   NULL,
  quantity_requested DECIMAL(14,4) NOT NULL,
  quantity_issued    DECIMAL(14,4) NULL,
  sort_order         INT           NOT NULL DEFAULT 0,
  KEY idx_kitchen_requisition_lines_parent (business_id, requisition_id, sort_order),
  KEY idx_kitchen_requisition_lines_item (business_id, item_id),
  CONSTRAINT fk_kitchen_requisition_lines_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);
