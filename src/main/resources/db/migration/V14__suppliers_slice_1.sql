-- Phase 2 Slice 1 — suppliers aggregate + item links (PHASE_2_PLAN.md §Slice 1).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000044', 'suppliers.read',
   'List and view suppliers and contacts.'),
  ('11111111-0000-0000-0000-000000000045', 'suppliers.write',
   'Create and edit suppliers and contacts.'),
  ('11111111-0000-0000-0000-000000000046', 'catalog.items.link_suppliers',
   'Link items to suppliers and manage primary supplier.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000045'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000046'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000045'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000046'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000045'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000046');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000044'),
  ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000044');

CREATE TABLE suppliers (
  id                          CHAR(36) PRIMARY KEY,
  business_id                 CHAR(36) NOT NULL,
  name                        VARCHAR(255) NOT NULL,
  code                        VARCHAR(64) NULL,
  supplier_type               VARCHAR(32) NOT NULL DEFAULT 'distributor',
  vat_pin                     VARCHAR(64) NULL,
  is_tax_exempt               BOOLEAN NOT NULL DEFAULT FALSE,
  credit_terms_days           INT NULL,
  credit_limit                DECIMAL(14, 2) NULL,
  rating                      DECIMAL(5, 2) NULL,
  status                      VARCHAR(16) NOT NULL DEFAULT 'active',
  notes                       TEXT NULL,
  payment_method_preferred    VARCHAR(32) NULL,
  payment_details             TEXT NULL,
  version                     BIGINT NOT NULL DEFAULT 0,
  created_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at                  TIMESTAMP NULL,
  UNIQUE KEY uq_suppliers_business_code (business_id, code),
  CONSTRAINT fk_suppliers_business FOREIGN KEY (business_id) REFERENCES businesses(id)
);

CREATE INDEX idx_suppliers_business_active ON suppliers (business_id, deleted_at, status);
CREATE INDEX idx_suppliers_business_name ON suppliers (business_id, name);

CREATE TABLE supplier_contacts (
  id              CHAR(36) PRIMARY KEY,
  supplier_id     CHAR(36) NOT NULL,
  name            VARCHAR(255) NULL,
  role_label      VARCHAR(128) NULL,
  phone           VARCHAR(64) NULL,
  email           VARCHAR(255) NULL,
  is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_supplier_contacts_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id)
);

CREATE INDEX idx_supplier_contacts_supplier ON supplier_contacts (supplier_id);

CREATE TABLE supplier_products (
  id                    CHAR(36) PRIMARY KEY,
  supplier_id           CHAR(36) NOT NULL,
  item_id               CHAR(36) NOT NULL,
  is_primary            BOOLEAN NOT NULL DEFAULT FALSE,
  supplier_sku          VARCHAR(191) NULL,
  default_cost_price    DECIMAL(14, 4) NULL,
  pack_size             DECIMAL(14, 4) NULL,
  pack_unit             VARCHAR(32) NULL,
  lead_time_days        INT NULL,
  min_order_qty         DECIMAL(14, 4) NULL,
  last_cost_price       DECIMAL(14, 4) NULL,
  last_purchase_at      TIMESTAMP NULL,
  active                BOOLEAN NOT NULL DEFAULT TRUE,
  version               BIGINT NOT NULL DEFAULT 0,
  created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at            TIMESTAMP NULL,
  UNIQUE KEY uq_supplier_products_pair (supplier_id, item_id),
  CONSTRAINT fk_supplier_products_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_supplier_products_item FOREIGN KEY (item_id) REFERENCES items(id)
);

CREATE INDEX idx_supplier_products_item ON supplier_products (item_id, deleted_at, active);
CREATE INDEX idx_supplier_products_supplier ON supplier_products (supplier_id, deleted_at);

-- Synthetic supplier per business + one primary link per stocked sellable item (PHASE_2_PLAN.md §Prerequisites).
INSERT INTO suppliers (
  id, business_id, name, code, supplier_type, status, notes, version, created_at, updated_at, deleted_at
)
SELECT UUID(), b.id, 'Unassigned (migrate)', 'SYS-UNASSIGNED', 'distributor', 'active',
       'Phase-2 backfill; replace with real suppliers and reassign primaries.', 0,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM suppliers s
    WHERE s.business_id = b.id AND s.code = 'SYS-UNASSIGNED' AND s.deleted_at IS NULL
  );

INSERT INTO supplier_products (
  id, supplier_id, item_id, is_primary, active, version, created_at, updated_at, deleted_at
)
SELECT UUID(), s.id, i.id, TRUE, TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL
FROM items i
JOIN suppliers s ON s.business_id = i.business_id
  AND s.code = 'SYS-UNASSIGNED'
  AND s.deleted_at IS NULL
WHERE i.deleted_at IS NULL
  AND i.is_sellable = TRUE
  AND i.is_stocked = TRUE
  AND NOT EXISTS (
    SELECT 1 FROM supplier_products sp
    WHERE sp.item_id = i.id AND sp.deleted_at IS NULL
  );
