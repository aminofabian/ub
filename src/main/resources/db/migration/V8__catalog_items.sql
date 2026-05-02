-- Slice 5 — Items catalog (PHASE_1_PLAN.md §5.1). MySQL 8: FULLTEXT for search.
-- H2 tests use ddl-auto; this migration targets MySQL prod.

CREATE TABLE items (
  id                     CHAR(36) PRIMARY KEY,
  business_id            CHAR(36) NOT NULL,
  sku                    VARCHAR(191) NOT NULL,
  barcode                VARCHAR(191) NULL,
  name                   VARCHAR(500) NOT NULL,
  description            TEXT NULL,
  variant_of_item_id     CHAR(36) NULL,
  variant_name           VARCHAR(255) NULL,
  category_id            CHAR(36) NULL,
  aisle_id               CHAR(36) NULL,
  item_type_id           CHAR(36) NOT NULL,
  unit_type              VARCHAR(16) NOT NULL DEFAULT 'each',
  is_weighed             BOOLEAN NOT NULL DEFAULT FALSE,
  is_sellable            BOOLEAN NOT NULL DEFAULT TRUE,
  is_stocked             BOOLEAN NOT NULL DEFAULT TRUE,
  packaging_unit_name    VARCHAR(255) NULL,
  packaging_unit_qty     DECIMAL(14,4) NULL,
  bundle_qty             INT NULL,
  bundle_price           DECIMAL(14,2) NULL,
  bundle_name            VARCHAR(255) NULL,
  min_stock_level        DECIMAL(14,4) NULL,
  reorder_level          DECIMAL(14,4) NULL,
  reorder_qty            DECIMAL(14,4) NULL,
  expires_after_days     INT NULL,
  has_expiry             BOOLEAN NOT NULL DEFAULT FALSE,
  image_key              VARCHAR(500) NULL,
  active                 BOOLEAN NOT NULL DEFAULT TRUE,
  version                BIGINT NOT NULL DEFAULT 0,
  created_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at             TIMESTAMP NULL,
  UNIQUE KEY uq_items_business_sku (business_id, sku),
  CONSTRAINT fk_items_business      FOREIGN KEY (business_id)        REFERENCES businesses(id),
  CONSTRAINT fk_items_variant_parent FOREIGN KEY (variant_of_item_id) REFERENCES items(id),
  CONSTRAINT fk_items_category       FOREIGN KEY (category_id)        REFERENCES categories(id),
  CONSTRAINT fk_items_aisle          FOREIGN KEY (aisle_id)           REFERENCES aisles(id),
  CONSTRAINT fk_items_item_type    FOREIGN KEY (item_type_id)       REFERENCES item_types(id)
);

CREATE INDEX idx_items_business_active ON items (business_id, active, deleted_at);
CREATE INDEX idx_items_business_barcode ON items (business_id, barcode);
CREATE INDEX idx_items_variant_parent   ON items (variant_of_item_id);

CREATE FULLTEXT INDEX ft_items_search ON items (name, variant_name, sku, barcode, description);

CREATE TABLE item_images (
  id          CHAR(36) PRIMARY KEY,
  item_id     CHAR(36) NOT NULL,
  s3_key      VARCHAR(500) NOT NULL,
  width       INT NULL,
  height      INT NULL,
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_item_images_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE INDEX idx_item_images_item ON item_images (item_id);

CREATE TABLE item_tags (
  item_id CHAR(36) NOT NULL,
  tag     VARCHAR(191) NOT NULL,
  PRIMARY KEY (item_id, tag),
  CONSTRAINT fk_item_tags_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

-- Idempotency replay for POST /api/v1/items (PHASE_1_PLAN.md §5.3).
CREATE TABLE idempotency_keys (
  id             CHAR(36) PRIMARY KEY,
  business_id    CHAR(36) NOT NULL,
  key_hash       CHAR(64) NOT NULL,
  route          VARCHAR(128) NOT NULL,
  body_hash      CHAR(64) NOT NULL,
  http_status    INT NOT NULL,
  response_json  MEDIUMTEXT NOT NULL,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_idempotency (business_id, key_hash, route),
  CONSTRAINT fk_idempotency_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);
