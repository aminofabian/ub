-- Catalog discount promotions (overlay on shelf price; does not mutate selling_prices).
-- Uses IF NOT EXISTS / IGNORE to be re-runnable after a partial prior attempt.

CREATE TABLE IF NOT EXISTS discounts (
  id              CHAR(36)     NOT NULL PRIMARY KEY,
  business_id     CHAR(36)     NOT NULL,
  name            VARCHAR(255) NOT NULL,
  description     TEXT         NULL,
  kind            VARCHAR(20)  NOT NULL DEFAULT 'STANDARD',
  method          VARCHAR(20)  NOT NULL COMMENT 'PERCENTAGE or FIXED_AMOUNT',
  value           DECIMAL(14,4) NOT NULL,
  scope           VARCHAR(20)  NOT NULL COMMENT 'ITEM, CATEGORY, SUPPLIER, STORE',
  branch_id       CHAR(36)     NULL COMMENT 'NULL = all branches',
  start_at        TIMESTAMP    NOT NULL,
  end_at          TIMESTAMP    NULL COMMENT 'NULL = ongoing',
  paused          BOOLEAN      NOT NULL DEFAULT FALSE,
  published_at    TIMESTAMP    NULL COMMENT 'NULL = draft',
  priority        INT          NOT NULL,
  version         BIGINT       NOT NULL DEFAULT 0,
  created_by      CHAR(36)     NULL,
  updated_by      CHAR(36)     NULL,
  created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_discounts_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS discount_items (
  discount_id  CHAR(36) NOT NULL,
  item_id      CHAR(36) NOT NULL,
  PRIMARY KEY (discount_id, item_id),
  CONSTRAINT fk_di_discount FOREIGN KEY (discount_id) REFERENCES discounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_di_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS discount_categories (
  discount_id   CHAR(36) NOT NULL,
  category_id   CHAR(36) NOT NULL,
  PRIMARY KEY (discount_id, category_id),
  CONSTRAINT fk_dc_discount FOREIGN KEY (discount_id) REFERENCES discounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_dc_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS discount_suppliers (
  discount_id          CHAR(36) NOT NULL,
  supplier_id          CHAR(36) NOT NULL,
  include_any_linked   BOOLEAN  NOT NULL DEFAULT FALSE,
  PRIMARY KEY (discount_id, supplier_id),
  CONSTRAINT fk_ds_discount FOREIGN KEY (discount_id) REFERENCES discounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_ds_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS discount_exclusions (
  discount_id  CHAR(36) NOT NULL,
  item_id      CHAR(36) NOT NULL,
  PRIMARY KEY (discount_id, item_id),
  CONSTRAINT fk_de_discount FOREIGN KEY (discount_id) REFERENCES discounts (id) ON DELETE CASCADE,
  CONSTRAINT fk_de_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE
);

-- sale_items columns (idempotent: MySQL 8+ ignores ADD COLUMN IF NOT EXISTS isn't supported,
-- so we use a stored procedure via Flyway's placeholder-aware script runner).
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sale_items' AND column_name = 'regular_unit_price');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE sale_items ADD COLUMN regular_unit_price DECIMAL(14,4) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sale_items' AND column_name = 'discount_amount');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE sale_items ADD COLUMN discount_amount DECIMAL(14,2) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sale_items' AND column_name = 'discount_id');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE sale_items ADD COLUMN discount_id CHAR(36) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sale_items' AND column_name = 'discount_name');
SET @sql = IF(@col_exists = 0, 'ALTER TABLE sale_items ADD COLUMN discount_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'sale_items' AND index_name = 'idx_sale_items_discount_id');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_sale_items_discount_id ON sale_items (discount_id)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'discounts' AND index_name = 'idx_discounts_business_status');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_discounts_business_status ON discounts (business_id, published_at, paused, start_at, end_at)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'discounts' AND index_name = 'idx_discounts_business_scope');
SET @sql = IF(@idx_exists = 0, 'CREATE INDEX idx_discounts_business_scope ON discounts (business_id, scope, branch_id)', 'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000093', 'pricing.discounts.manage',
   'Create, edit, publish, and pause catalog discount promotions.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'pricing.discounts.manage'
  AND r.role_key IN ('owner', 'admin', 'manager')
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
