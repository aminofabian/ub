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
-- This migration runs ONLY on MariaDB when legacy STORED GENERATED columns are
-- still present. Cloud MySQL (Coolify) must skip it entirely:
--   - MySQL 8 still supports the old STORED GENERATED definitions.
--   - CREATE TRIGGER on managed MySQL with binary logging fails with error 1419
--     unless log_bin_trust_function_creators=1.
-- Fresh installs (cloud or desktop) already have plain columns + triggers from V1/V2.

SET @is_mariadb = (SELECT IF(VERSION() LIKE '%MariaDB%', 1, 0));

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

SET @run_conv = (@is_mariadb > 0 AND @col_is_generated > 0);

SET @sql = IF(
  @run_conv > 0,
  'ALTER TABLE domains DROP INDEX uq_domains_primary, DROP COLUMN primary_business_id, ADD COLUMN primary_business_id CHAR(36) NULL',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'DROP TRIGGER IF EXISTS trg_domains_primary_bi',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'DROP TRIGGER IF EXISTS trg_domains_primary_bu',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'CREATE TRIGGER trg_domains_primary_bi BEFORE INSERT ON domains FOR EACH ROW SET NEW.primary_business_id = IF(NEW.is_primary, NEW.business_id, NULL)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'CREATE TRIGGER trg_domains_primary_bu BEFORE UPDATE ON domains FOR EACH ROW SET NEW.primary_business_id = IF(NEW.is_primary, NEW.business_id, NULL)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'UPDATE domains SET primary_business_id = IF(is_primary, business_id, NULL) WHERE primary_business_id IS NULL AND is_primary',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'domains'
    AND INDEX_NAME = 'uq_domains_primary'
);
SET @sql = IF(
  @run_conv > 0 AND @idx_exists = 0,
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

SET @run_conv = (@is_mariadb > 0 AND @col_is_generated > 0);

SET @sql = IF(
  @run_conv > 0,
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
  @run_conv > 0,
  'UPDATE roles SET business_scope = COALESCE(business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'DROP TRIGGER IF EXISTS trg_roles_scope_bi',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'DROP TRIGGER IF EXISTS trg_roles_scope_bu',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'CREATE TRIGGER trg_roles_scope_bi BEFORE INSERT ON roles FOR EACH ROW SET NEW.business_scope = COALESCE(NEW.business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @run_conv > 0,
  'CREATE TRIGGER trg_roles_scope_bu BEFORE UPDATE ON roles FOR EACH ROW SET NEW.business_scope = COALESCE(NEW.business_id, ''00000000-0000-0000-0000-000000000000'')',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'roles'
    AND INDEX_NAME = 'uq_roles_scope_key'
);
SET @sql = IF(
  @run_conv > 0 AND @idx_exists = 0,
  'CREATE UNIQUE INDEX uq_roles_scope_key ON roles (business_scope, role_key)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
