-- DESKTOP_INSTALLATION.md §7 / step 4: the desktop SKU bundles MariaDB 10.11,
-- which rejects STORED GENERATED columns whose expression invokes any function
-- on a CHAR(n) input (CASE / IF / COALESCE / CONCAT — all fail with error 1901,
-- ER_GENERATED_COLUMN_FUNCTION_IS_NOT_ALLOWED).
--
-- V1 and V2 originally created two such columns:
--   - domains.primary_business_id  (CASE WHEN is_primary THEN business_id ELSE NULL END)
--   - roles.business_scope         (COALESCE(business_id, '00000000-...'))
-- Their V1 / V2 sources were rewritten in this same change to use plain
-- columns + BEFORE INSERT/UPDATE triggers, which work identically on MySQL 8
-- and MariaDB 10.11+.
--
-- For fresh installs (cloud MySQL or desktop MariaDB) this migration is a
-- no-op — V1/V2 already produced the new shape and the triggers / index exist.
--
-- For pre-existing cloud MySQL DBs the columns are still STORED GENERATED with
-- their old definitions. This migration converts them in place. It is
-- idempotent: the DROP / CREATE pairs use IF EXISTS where MySQL/MariaDB
-- support it, and the column rewrite is gated on EXTRA LIKE '%GENERATED%'
-- against information_schema so we don't accidentally drop a freshly-created
-- plain column.
--
-- IMPORTANT (cloud MySQL): trigger CREATE only runs when legacy STORED GENERATED
-- columns are being converted (@col_is_generated > 0). Fresh installs skip
-- trigger DDL entirely — V1/V2 already created the triggers. Recreating them
-- on every deploy fails on managed MySQL when binary logging is enabled unless
-- the server has log_bin_trust_function_creators=1 (error 1419).
--
-- Legacy cloud DBs that still have STORED GENERATED columns need either:
--   SET GLOBAL log_bin_trust_function_creators = 1;
-- or a one-time manual trigger migration by a privileged user.

-- ---------------------------------------------------------------------------
-- domains.primary_business_id
-- ---------------------------------------------------------------------------
SET @col_is_generated = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'domains'
    AND COLUMN_NAME = 'primary_business_id'
    AND EXTRA LIKE '%GENERATED%'
);

SET @sql = IF(
  @col_is_generated > 0,
  'ALTER TABLE domains DROP INDEX uq_domains_primary, DROP COLUMN primary_business_id, ADD COLUMN primary_business_id CHAR(36) NULL',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'DROP TRIGGER IF EXISTS trg_domains_primary_bi',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'DROP TRIGGER IF EXISTS trg_domains_primary_bu',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'CREATE TRIGGER trg_domains_primary_bi BEFORE INSERT ON domains FOR EACH ROW SET NEW.primary_business_id = IF(NEW.is_primary, NEW.business_id, NULL)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'CREATE TRIGGER trg_domains_primary_bu BEFORE UPDATE ON domains FOR EACH ROW SET NEW.primary_business_id = IF(NEW.is_primary, NEW.business_id, NULL)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'UPDATE domains SET primary_business_id = IF(is_primary, business_id, NULL) WHERE primary_business_id IS NULL AND is_primary',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'domains'
    AND INDEX_NAME = 'uq_domains_primary'
);
SET @sql = IF(@idx_exists = 0,
  'CREATE UNIQUE INDEX uq_domains_primary ON domains (primary_business_id)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------------
-- roles.business_scope
-- ---------------------------------------------------------------------------
SET @col_is_generated = (
  SELECT COUNT(*)
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'roles'
    AND COLUMN_NAME = 'business_scope'
    AND EXTRA LIKE '%GENERATED%'
);

SET @sql = IF(
  @col_is_generated > 0,
  CONCAT(
    'ALTER TABLE roles ',
    'DROP INDEX uq_roles_scope_key, ',
    'DROP COLUMN business_scope, ',
    'ADD COLUMN business_scope CHAR(36) NOT NULL DEFAULT ''00000000-0000-0000-0000-000000000000'''
  ),
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'UPDATE roles SET business_scope = COALESCE(business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'DROP TRIGGER IF EXISTS trg_roles_scope_bi',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'DROP TRIGGER IF EXISTS trg_roles_scope_bu',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'CREATE TRIGGER trg_roles_scope_bi BEFORE INSERT ON roles FOR EACH ROW SET NEW.business_scope = COALESCE(NEW.business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @col_is_generated > 0,
  'CREATE TRIGGER trg_roles_scope_bu BEFORE UPDATE ON roles FOR EACH ROW SET NEW.business_scope = COALESCE(NEW.business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles'
    AND INDEX_NAME = 'uq_roles_scope_key'
);
SET @sql = IF(@idx_exists = 0,
  'CREATE UNIQUE INDEX uq_roles_scope_key ON roles (business_scope, role_key)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
