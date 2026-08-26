-- First-class multi-pack purchase options on catalog items (docs/MULTI_PACK_OPTIONS_SCOPE.md §5).
-- One item can be bought loose (no row) or as one of several pack sizes (12 / 18 / 48 …).

CREATE TABLE item_pack_options (
  id                   CHAR(36) PRIMARY KEY,
  business_id          CHAR(36) NOT NULL,
  item_id              CHAR(36) NOT NULL,
  label                VARCHAR(255) NULL,
  pack_unit            VARCHAR(32) NOT NULL,
  units_per_pack       DECIMAL(14, 4) NOT NULL,
  default_pack_price   DECIMAL(14, 4) NULL,
  barcode              VARCHAR(191) NULL,
  sku_suffix           VARCHAR(64) NULL,
  sort_order           INT NOT NULL DEFAULT 0,
  active               BOOLEAN NOT NULL DEFAULT TRUE,
  version              BIGINT NOT NULL DEFAULT 0,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_item_pack_options_shape (business_id, item_id, units_per_pack, pack_unit),
  CONSTRAINT fk_item_pack_options_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE,
  CONSTRAINT chk_item_pack_options_units CHECK (units_per_pack > 1)
);

CREATE INDEX idx_item_pack_options_item ON item_pack_options (item_id, sort_order, active);
