-- Public marketplace supplier slugs (cross-tenant unique when set).
-- Idempotent: safe if a previous deploy added the column manually.

SET @col_exists := (
  SELECT COUNT(*)
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'suppliers'
    AND COLUMN_NAME = 'public_slug'
);

SET @sql := IF(
  @col_exists = 0,
  'ALTER TABLE suppliers ADD COLUMN public_slug VARCHAR(96) NULL',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*)
  FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'suppliers'
    AND INDEX_NAME = 'uk_suppliers_public_slug'
);

SET @sql := IF(
  @idx_exists = 0,
  'CREATE UNIQUE INDEX uk_suppliers_public_slug ON suppliers (public_slug)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
