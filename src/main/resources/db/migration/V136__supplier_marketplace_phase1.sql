-- Phase 1 — Supplier marketplace core schema (SUPPLIER_MARKETPLACE_SCOPE.md §20, Sprint 1–2).
-- Platform supplier registry, business connections, identity index, supplier portal auth,
-- and Path A PO extensions for supplier line-level response.

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000500', 'marketplace.suppliers.read',
   'Search marketplace supplier directory and view supplier profiles.'),
  ('11111111-0000-0000-0000-000000000501', 'marketplace.suppliers.connect',
   'Connect a marketplace supplier to the business.'),
  ('11111111-0000-0000-0000-000000000520', 'marketplace.admin.read',
   'View platform marketplace supplier analytics.'),
  ('11111111-0000-0000-0000-000000000521', 'marketplace.admin.write',
   'Onboard and manage marketplace suppliers.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN ('marketplace.suppliers.read', 'marketplace.suppliers.connect')
  AND r.role_key IN ('owner', 'admin', 'manager', 'stock_manager');

-- ---------------------------------------------------------------------------
-- Platform supplier registry
-- ---------------------------------------------------------------------------

CREATE TABLE marketplace_suppliers (
  id              CHAR(36) PRIMARY KEY,
  name            VARCHAR(255) NOT NULL,
  description     TEXT NULL,
  contact_email   VARCHAR(255) NULL,
  contact_phone   VARCHAR(32) NULL,
  status          VARCHAR(16) NOT NULL DEFAULT 'draft',
  delivery_regions_json TEXT NULL,
  category_tags_json    TEXT NULL,
  trust_score     DECIMAL(5,2) NULL,
  version         BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE INDEX idx_marketplace_suppliers_status ON marketplace_suppliers (status);
CREATE INDEX idx_marketplace_suppliers_name ON marketplace_suppliers (name);

CREATE TABLE marketplace_supplier_products (
  id                      CHAR(36) PRIMARY KEY,
  marketplace_supplier_id CHAR(36) NOT NULL,
  name                    VARCHAR(500) NOT NULL,
  barcode                 VARCHAR(191) NULL,
  sku                     VARCHAR(191) NULL,
  category_name           VARCHAR(255) NULL,
  description             TEXT NULL,
  pack_size               DECIMAL(14,4) NULL,
  pack_unit               VARCHAR(32) NULL,
  min_order_qty           DECIMAL(14,4) NULL,
  status                  VARCHAR(16) NOT NULL DEFAULT 'active',
  version                 BIGINT NOT NULL DEFAULT 0,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_msp_supplier FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id),
  INDEX idx_msp_supplier_status (marketplace_supplier_id, status),
  INDEX idx_msp_barcode (barcode),
  FULLTEXT INDEX idx_msp_search (name, sku, barcode)
);

CREATE TABLE marketplace_supplier_price_offers (
  id                      CHAR(36) PRIMARY KEY,
  marketplace_supplier_id CHAR(36) NOT NULL,
  product_id              CHAR(36) NOT NULL,
  package_size            DECIMAL(14,4) NOT NULL,
  package_unit            VARCHAR(32) NOT NULL,
  region_code             VARCHAR(32) NULL,
  min_qty                 DECIMAL(14,4) NOT NULL DEFAULT 1,
  unit_price              DECIMAL(14,4) NOT NULL,
  currency                CHAR(3) NOT NULL DEFAULT 'KES',
  available               BOOLEAN NOT NULL DEFAULT TRUE,
  effective_from          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_to            TIMESTAMP NULL,
  version                 BIGINT NOT NULL DEFAULT 0,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_mspo_supplier FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id),
  CONSTRAINT fk_mspo_product  FOREIGN KEY (product_id) REFERENCES marketplace_supplier_products(id) ON DELETE CASCADE,
  INDEX idx_mspo_supplier_product (marketplace_supplier_id, product_id, available)
);

-- ---------------------------------------------------------------------------
-- Supplier portal users
-- ---------------------------------------------------------------------------

CREATE TABLE supplier_users (
  id                      CHAR(36) PRIMARY KEY,
  marketplace_supplier_id CHAR(36) NOT NULL,
  email                   VARCHAR(191) NOT NULL,
  name                    VARCHAR(255) NOT NULL,
  password_hash           VARCHAR(255) NOT NULL,
  role_key                VARCHAR(32) NOT NULL DEFAULT 'admin',
  active                  BOOLEAN NOT NULL DEFAULT TRUE,
  last_login_at           TIMESTAMP NULL,
  failed_attempts         INT NOT NULL DEFAULT 0,
  locked_until            TIMESTAMP NULL,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_supplier_users_email (email),
  CONSTRAINT fk_supplier_users_supplier FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id)
);

CREATE INDEX idx_supplier_users_supplier ON supplier_users (marketplace_supplier_id, active);

-- ---------------------------------------------------------------------------
-- Business ↔ marketplace connections
-- ---------------------------------------------------------------------------

CREATE TABLE business_supplier_connections (
  id                        CHAR(36) PRIMARY KEY,
  business_id               CHAR(36) NOT NULL,
  marketplace_supplier_id   CHAR(36) NOT NULL,
  local_supplier_id         CHAR(36) NOT NULL,
  status                    VARCHAR(16) NOT NULL DEFAULT 'pending',
  can_view_stock_levels     BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_low_stock_alerts BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_sales_velocity   BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_demand_forecast  BOOLEAN NOT NULL DEFAULT FALSE,
  can_suggest_restock       BOOLEAN NOT NULL DEFAULT FALSE,
  can_create_draft_po       BOOLEAN NOT NULL DEFAULT FALSE,
  can_view_purchase_history BOOLEAN NOT NULL DEFAULT TRUE,
  created_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_bsc_business_marketplace (business_id, marketplace_supplier_id),
  UNIQUE KEY uq_bsc_local_supplier (local_supplier_id),
  CONSTRAINT fk_bsc_business   FOREIGN KEY (business_id)             REFERENCES businesses(id),
  CONSTRAINT fk_bsc_marketplace FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id),
  CONSTRAINT fk_bsc_local       FOREIGN KEY (local_supplier_id)       REFERENCES suppliers(id)
);

CREATE INDEX idx_bsc_marketplace ON business_supplier_connections (marketplace_supplier_id, status);

-- Link existing business suppliers to marketplace (nullable — private suppliers stay unlinked).
ALTER TABLE suppliers
  ADD COLUMN marketplace_supplier_id CHAR(36) NULL,
  ADD CONSTRAINT fk_suppliers_marketplace
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id);

CREATE INDEX idx_suppliers_marketplace ON suppliers (marketplace_supplier_id);

-- ---------------------------------------------------------------------------
-- Cross-tenant supplier identity index (§4)
-- ---------------------------------------------------------------------------

CREATE TABLE supplier_identity_index (
  id                      CHAR(36) PRIMARY KEY,
  source                  VARCHAR(16) NOT NULL,
  business_id             CHAR(36) NULL,
  supplier_id             CHAR(36) NULL,
  marketplace_supplier_id   CHAR(36) NULL,
  name_normalized         VARCHAR(255) NOT NULL,
  phone_normalized        VARCHAR(32) NULL,
  email_normalized        VARCHAR(255) NULL,
  tax_id_normalized       VARCHAR(64) NULL,
  region_hint             VARCHAR(64) NULL,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_sii_tax_id (tax_id_normalized),
  INDEX idx_sii_phone (phone_normalized),
  INDEX idx_sii_email (email_normalized),
  INDEX idx_sii_name (name_normalized),
  CONSTRAINT fk_sii_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id) ON DELETE CASCADE,
  CONSTRAINT fk_sii_marketplace FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id) ON DELETE CASCADE
);

-- Backfill tenant supplier identity rows from existing suppliers.
INSERT INTO supplier_identity_index (
  id, source, business_id, supplier_id, marketplace_supplier_id,
  name_normalized, phone_normalized, email_normalized, tax_id_normalized,
  region_hint, created_at, updated_at
)
SELECT
  UUID(),
  'tenant',
  s.business_id,
  s.id,
  NULL,
  LOWER(TRIM(s.name)),
  NULLIF(TRIM(s.payout_phone), ''),
  NULL,
  NULLIF(UPPER(REPLACE(REPLACE(TRIM(s.vat_pin), ' ', ''), '-', '')), ''),
  NULL,
  s.created_at,
  s.updated_at
FROM suppliers s
WHERE s.deleted_at IS NULL;

-- Backfill primary contact phone/email onto identity rows where present.
UPDATE supplier_identity_index sii
JOIN supplier_contacts sc ON sc.supplier_id = sii.supplier_id AND sc.is_primary = TRUE
SET
  sii.phone_normalized = COALESCE(NULLIF(TRIM(sii.phone_normalized), ''), NULLIF(TRIM(sc.phone), '')),
  sii.email_normalized = COALESCE(NULLIF(LOWER(TRIM(sii.email_normalized)), ''), NULLIF(LOWER(TRIM(sc.email)), '')),
  sii.updated_at = CURRENT_TIMESTAMP
WHERE sii.source = 'tenant';

-- ---------------------------------------------------------------------------
-- Supplier performance events (instrument from Phase 1)
-- ---------------------------------------------------------------------------

CREATE TABLE supplier_performance_events (
  id                      CHAR(36) PRIMARY KEY,
  marketplace_supplier_id CHAR(36) NOT NULL,
  business_id             CHAR(36) NULL,
  event_type              VARCHAR(64) NOT NULL,
  payload_json            TEXT NULL,
  created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_spe_supplier FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers(id),
  INDEX idx_spe_supplier_type (marketplace_supplier_id, event_type, created_at)
);

-- ---------------------------------------------------------------------------
-- Path A PO extensions (§9)
-- ---------------------------------------------------------------------------

ALTER TABLE purchase_orders
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'manual',
  ADD COLUMN sent_to_supplier_at TIMESTAMP NULL,
  ADD COLUMN supplier_response_at TIMESTAMP NULL,
  ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'not_shipped';

ALTER TABLE purchase_order_lines
  ADD COLUMN supplier_line_status VARCHAR(32) NOT NULL DEFAULT 'pending',
  ADD COLUMN qty_accepted DECIMAL(14,4) NULL,
  ADD COLUMN supplier_note TEXT NULL;
