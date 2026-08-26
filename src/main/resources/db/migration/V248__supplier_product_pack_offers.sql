-- Per-link offer of item pack options: a supplier link can override the pack price
-- and decide which of the item's pack shapes it actually sells (docs/MULTI_PACK_OPTIONS_SCOPE.md §5.2).

CREATE TABLE supplier_product_pack_offers (
  id                    CHAR(36) PRIMARY KEY,
  supplier_product_id   CHAR(36) NOT NULL,
  item_pack_option_id   CHAR(36) NOT NULL,
  pack_price            DECIMAL(14, 4) NULL,
  active                BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order            INT NOT NULL DEFAULT 0,
  version               BIGINT NOT NULL DEFAULT 0,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_supplier_product_pack_offers_pair (supplier_product_id, item_pack_option_id),
  CONSTRAINT fk_supplier_product_pack_offers_link FOREIGN KEY (supplier_product_id) REFERENCES supplier_products(id) ON DELETE CASCADE,
  CONSTRAINT fk_supplier_product_pack_offers_option FOREIGN KEY (item_pack_option_id) REFERENCES item_pack_options(id) ON DELETE CASCADE
);

CREATE INDEX idx_supplier_product_pack_offers_link ON supplier_product_pack_offers (supplier_product_id, active);
