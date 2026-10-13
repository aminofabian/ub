-- =============================================================================
-- V351 — Full-count cycles: count the whole catalog in daily batches
-- (e.g. 100 products a day) until every stocked product has been counted.
-- MySQL-compatible idempotent migration.
-- =============================================================================

CREATE TABLE IF NOT EXISTS stock_count_cycles (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    business_id       VARCHAR(36)  NOT NULL,
    branch_id         VARCHAR(36)  NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    batch_size        INT          NOT NULL,
    total_items       INT          NOT NULL,
    counted_items     INT          NOT NULL DEFAULT 0,
    batches_started   INT          NOT NULL DEFAULT 0,
    active_session_id VARCHAR(36),
    started_by        VARCHAR(36),
    started_at        TIMESTAMP(6) NOT NULL,
    completed_at      TIMESTAMP(6),
    cancelled_at      TIMESTAMP(6),
    version           BIGINT       NOT NULL DEFAULT 0
);

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_count_cycles'
     AND index_name = 'idx_stock_count_cycles_branch_status'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_stock_count_cycles_branch_status ON stock_count_cycles (business_id, branch_id, status)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS stock_count_cycle_items (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    cycle_id     VARCHAR(36)  NOT NULL,
    item_id      VARCHAR(36)  NOT NULL,
    sort_order   INT          NOT NULL,
    session_id   VARCHAR(36),
    counted_at   TIMESTAMP(6),
    CONSTRAINT uq_stock_count_cycle_item UNIQUE (cycle_id, item_id),
    CONSTRAINT fk_stock_count_cycle_item_cycle FOREIGN KEY (cycle_id) REFERENCES stock_count_cycles (id)
);

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stock_take_sessions' AND COLUMN_NAME = 'count_cycle_id') = 0,
  'ALTER TABLE stock_take_sessions ADD COLUMN count_cycle_id VARCHAR(36)',
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_sessions'
     AND index_name = 'idx_stock_take_sessions_count_cycle'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_stock_take_sessions_count_cycle ON stock_take_sessions (business_id, count_cycle_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
