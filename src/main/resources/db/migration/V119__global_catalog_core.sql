-- Global product catalog platform tables.
-- Tenant products remain in the existing `items` table; these tables hold
-- platform-curated templates that businesses can adopt into their own catalog.

CREATE TABLE global_catalogs (
  id          CHAR(36) PRIMARY KEY,
  code        VARCHAR(64) NOT NULL UNIQUE,
  name        VARCHAR(255) NOT NULL,
  region_code CHAR(2) NULL,
  currency    CHAR(3) NOT NULL DEFAULT 'KES',
  status      VARCHAR(16) NOT NULL DEFAULT 'published',
  version     INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE global_categories (
  id                      CHAR(36) PRIMARY KEY,
  catalog_id              CHAR(36) NOT NULL,
  parent_id               CHAR(36) NULL,
  name                    VARCHAR(255) NOT NULL,
  slug                    VARCHAR(191) NOT NULL,
  position                INT NOT NULL DEFAULT 0,
  tenant_category_slug_hint VARCHAR(191) NULL,
  image_url               VARCHAR(2048) NULL,
  active                  BOOLEAN NOT NULL DEFAULT TRUE,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_global_categories_catalog_slug (catalog_id, slug),
  CONSTRAINT fk_global_categories_catalog FOREIGN KEY (catalog_id) REFERENCES global_catalogs(id),
  CONSTRAINT fk_global_categories_parent  FOREIGN KEY (parent_id)  REFERENCES global_categories(id)
);

CREATE TABLE global_products (
  id                       CHAR(36) PRIMARY KEY,
  catalog_id               CHAR(36) NOT NULL,
  global_category_id       CHAR(36) NULL,
  sku_template             VARCHAR(191) NULL,
  name                     VARCHAR(500) NOT NULL,
  brand                    VARCHAR(255) NULL,
  size                     VARCHAR(50) NULL,
  description              TEXT NULL,
  barcode                  VARCHAR(191) NULL,
  unit_type                VARCHAR(16) NOT NULL DEFAULT 'each',
  is_weighed               BOOLEAN NOT NULL DEFAULT FALSE,
  is_sellable              BOOLEAN NOT NULL DEFAULT TRUE,
  is_stocked               BOOLEAN NOT NULL DEFAULT TRUE,
  recommended_buying_price  DECIMAL(14,2) NULL,
  recommended_selling_price DECIMAL(14,2) NULL,
  suggested_margin_pct      DECIMAL(5,2) NULL,
  default_reorder_level     DECIMAL(14,4) NULL,
  default_reorder_qty       DECIMAL(14,4) NULL,
  default_min_stock_level   DECIMAL(14,4) NULL,
  has_expiry                BOOLEAN NOT NULL DEFAULT FALSE,
  expires_after_days        INT NULL,
  image_url                 VARCHAR(2048) NULL,
  item_type_key_hint        VARCHAR(64) NULL DEFAULT 'goods',
  status                    VARCHAR(16) NOT NULL DEFAULT 'published',
  sort_order                INT NOT NULL DEFAULT 0,
  version                   BIGINT NOT NULL DEFAULT 0,
  created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_global_products_catalog_status (catalog_id, status),
  INDEX idx_global_products_catalog_barcode (catalog_id, barcode),
  FULLTEXT INDEX idx_global_products_search (name, brand, barcode),
  CONSTRAINT fk_global_products_catalog   FOREIGN KEY (catalog_id)         REFERENCES global_catalogs(id),
  CONSTRAINT fk_global_products_category  FOREIGN KEY (global_category_id) REFERENCES global_categories(id)
);

CREATE TABLE global_product_packs (
  id          CHAR(36) PRIMARY KEY,
  catalog_id  CHAR(36) NOT NULL,
  code        VARCHAR(64) NOT NULL,
  name        VARCHAR(255) NOT NULL,
  description TEXT NULL,
  store_kit_id VARCHAR(64) NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'published',
  sort_order  INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_global_product_packs_catalog_code (catalog_id, code),
  CONSTRAINT fk_global_product_packs_catalog FOREIGN KEY (catalog_id) REFERENCES global_catalogs(id)
);

CREATE TABLE global_product_pack_items (
  pack_id           CHAR(36) NOT NULL,
  global_product_id CHAR(36) NOT NULL,
  sort_order        INT NOT NULL DEFAULT 0,
  PRIMARY KEY (pack_id, global_product_id),
  CONSTRAINT fk_gppi_pack    FOREIGN KEY (pack_id)           REFERENCES global_product_packs(id),
  CONSTRAINT fk_gppi_product FOREIGN KEY (global_product_id) REFERENCES global_products(id)
);

CREATE TABLE global_supplier_templates (
  id            CHAR(36) PRIMARY KEY,
  catalog_id    CHAR(36) NOT NULL,
  code          VARCHAR(64) NOT NULL,
  name          VARCHAR(255) NOT NULL,
  supplier_type VARCHAR(32) NOT NULL DEFAULT 'distributor',
  vat_pin       VARCHAR(64) NULL,
  notes         TEXT NULL,
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_global_supplier_templates_catalog_code (catalog_id, code),
  CONSTRAINT fk_global_supplier_templates_catalog FOREIGN KEY (catalog_id) REFERENCES global_catalogs(id)
);

CREATE TABLE global_product_supplier_links (
  global_product_id          CHAR(36) NOT NULL,
  global_supplier_template_id CHAR(36) NOT NULL,
  is_primary                 BOOLEAN NOT NULL DEFAULT FALSE,
  default_cost_price         DECIMAL(14,4) NULL,
  supplier_sku               VARCHAR(191) NULL,
  PRIMARY KEY (global_product_id, global_supplier_template_id),
  CONSTRAINT fk_gpsl_product  FOREIGN KEY (global_product_id)          REFERENCES global_products(id),
  CONSTRAINT fk_gpsl_supplier FOREIGN KEY (global_supplier_template_id) REFERENCES global_supplier_templates(id)
);
