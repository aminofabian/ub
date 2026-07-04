-- =============================================================================
-- V133 — Daily stock audit: manifest table, session/line extensions.
-- MySQL-compatible idempotent migration.
-- =============================================================================

CREATE TABLE IF NOT EXISTS daily_stock_audits (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    business_id  VARCHAR(36)  NOT NULL,
    branch_id    VARCHAR(36)  NOT NULL,
    audit_date   DATE         NOT NULL,
    item_count   INT          NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    generated_by VARCHAR(36)  NOT NULL,
    CONSTRAINT uq_daily_stock_audit_branch_date UNIQUE (business_id, branch_id, audit_date)
);

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'daily_stock_audits'
     AND index_name = 'idx_daily_stock_audits_business_date'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_daily_stock_audits_business_date ON daily_stock_audits (business_id, audit_date)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS daily_stock_audit_items (
    audit_id   VARCHAR(36) NOT NULL,
    item_id    VARCHAR(36) NOT NULL,
    sort_order INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (audit_id, item_id),
    CONSTRAINT fk_daily_audit_item_audit FOREIGN KEY (audit_id) REFERENCES daily_stock_audits (id),
    CONSTRAINT fk_daily_audit_item_item FOREIGN KEY (item_id) REFERENCES items (id)
);

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'source') = 0,
  "ALTER TABLE stock_take_sessions ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'manual'",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'daily_audit_id') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN daily_audit_id VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'current_line_index') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN current_line_index INT',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @constraint_exists := (
  SELECT COUNT(*) FROM information_schema.table_constraints
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_sessions'
     AND constraint_name = 'fk_stock_take_session_daily_audit'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE stock_take_sessions ADD CONSTRAINT fk_stock_take_session_daily_audit FOREIGN KEY (daily_audit_id) REFERENCES daily_stock_audits (id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'review_status') = 0,
  "ALTER TABLE stock_take_lines ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'pending'",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'review_notes') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN review_notes TEXT',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'reviewed_by') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN reviewed_by VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_lines' AND COLUMN_NAME = 'reviewed_at') = 0,
  'ALTER TABLE stock_take_lines ADD COLUMN reviewed_at TIMESTAMP(6)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
