-- Restaurant operations, phase 1.5 (recipes and food cost) — ADR 0020.
--
-- One new table. A recipe line is an ingredient of a dish or of a modifier option; exactly one of
-- menu_item_id and modifier_option_id is set. When a dish is sold its ingredients leave the stock
-- count through stock_movements (reference_type 'sale_recipe'), which is also how a void finds
-- what to put back, so no "uses" table is needed.
--
-- Forward fix: delete nothing; a dish with no recipe lines simply costs nothing to make.

CREATE TABLE IF NOT EXISTS recipe_lines (
  id                  CHAR(36)      NOT NULL PRIMARY KEY,
  business_id         CHAR(36)      NOT NULL,
  menu_item_id        CHAR(36)      NULL,
  modifier_option_id  CHAR(36)      NULL,
  item_id             CHAR(36)      NOT NULL,
  quantity            DECIMAL(14,4) NOT NULL,
  yield_percent       DECIMAL(5,2)  NULL,
  sort_order          INT           NOT NULL DEFAULT 0,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_recipe_lines_business_dish (business_id, menu_item_id, sort_order),
  KEY idx_recipe_lines_business_option (business_id, modifier_option_id, sort_order),
  KEY idx_recipe_lines_business_item (business_id, item_id),
  CONSTRAINT fk_recipe_lines_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);
