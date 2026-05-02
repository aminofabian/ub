-- Slice 4 — Catalog taxonomy (PHASE_1_PLAN.md §4.1). MySQL: no RLS.
-- Item type discriminator column is `type_key` (plan: `key`; avoids MySQL reserved `KEY`).

CREATE TABLE categories (
  id           CHAR(36) PRIMARY KEY,
  business_id  CHAR(36) NOT NULL,
  name         VARCHAR(500) NOT NULL,
  slug         VARCHAR(191) NOT NULL,
  position     INT NOT NULL DEFAULT 0,
  icon         VARCHAR(500) NULL,
  parent_id    CHAR(36) NULL,
  active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_categories_business_slug (business_id, slug),
  CONSTRAINT fk_categories_business FOREIGN KEY (business_id) REFERENCES businesses(id),
  CONSTRAINT fk_categories_parent   FOREIGN KEY (parent_id)   REFERENCES categories(id)
);

CREATE INDEX idx_categories_business ON categories (business_id);
CREATE INDEX idx_categories_parent   ON categories (parent_id);

CREATE TABLE aisles (
  id           CHAR(36) PRIMARY KEY,
  business_id  CHAR(36) NOT NULL,
  name         VARCHAR(255) NOT NULL,
  code         VARCHAR(191) NOT NULL,
  sort_order   INT NOT NULL DEFAULT 0,
  active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_aisles_business_code (business_id, code),
  CONSTRAINT fk_aisles_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_aisles_business ON aisles (business_id);

CREATE TABLE item_types (
  id           CHAR(36) PRIMARY KEY,
  business_id  CHAR(36) NOT NULL,
  type_key     VARCHAR(191) NOT NULL,
  label        VARCHAR(255) NOT NULL,
  icon         VARCHAR(500) NULL,
  color        VARCHAR(64) NULL,
  sort_order   INT NOT NULL DEFAULT 0,
  active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_item_types_business_key (business_id, type_key),
  CONSTRAINT fk_item_types_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_item_types_business ON item_types (business_id);

-- Default item types (goods, service, kit) are inserted in application code when a
-- business is created (TenancyService + CatalogBootstrapService) so H2 tests and
-- Flyway stay aligned without MySQL trigger DELIMITER quirks.
