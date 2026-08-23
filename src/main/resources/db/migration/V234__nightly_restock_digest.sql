-- =============================================================================
-- V234 — Nightly restock digest: branch settings + run / suggestion artifacts.
-- MySQL-compatible idempotent migration (mirrors V133/V134 style).
-- =============================================================================

-- 1. Branch settings -----------------------------------------------------------
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'branches' AND COLUMN_NAME = 'restock_enabled') = 0,
  "ALTER TABLE branches ADD COLUMN restock_enabled BOOLEAN NOT NULL DEFAULT TRUE",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'branches' AND COLUMN_NAME = 'restock_run_time') = 0,
  "ALTER TABLE branches ADD COLUMN restock_run_time TIME NOT NULL DEFAULT '20:00:00'",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'branches' AND COLUMN_NAME = 'restock_cover_days') = 0,
  "ALTER TABLE branches ADD COLUMN restock_cover_days INT NOT NULL DEFAULT 3",
  'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. restock_runs -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restock_runs (
    id             CHAR(36)       NOT NULL PRIMARY KEY,
    business_id    CHAR(36)       NOT NULL,
    branch_id      CHAR(36)       NOT NULL,
    run_date       DATE           NOT NULL,
    generated_at   TIMESTAMP(6)   NOT NULL,
    status         VARCHAR(32)    NOT NULL DEFAULT 'generated',
    line_count     INT            NOT NULL DEFAULT 0,
    po_line_count  INT            NOT NULL DEFAULT 0,
    pad_line_count INT            NOT NULL DEFAULT 0,
    est_total      DECIMAL(14,4)  NOT NULL DEFAULT 0,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'KES',
    trigger        VARCHAR(16)    NOT NULL DEFAULT 'scheduled',
    error_note     TEXT           NULL,
    created_at     TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at     TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version        BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_restock_run_branch_date UNIQUE (branch_id, run_date),
    CONSTRAINT fk_restock_run_business FOREIGN KEY (business_id) REFERENCES businesses (id),
    CONSTRAINT fk_restock_run_branch   FOREIGN KEY (branch_id)   REFERENCES branches (id)
);

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'restock_runs'
     AND index_name = 'idx_restock_runs_business_date'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_runs_business_date ON restock_runs (business_id, run_date)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'restock_runs'
     AND index_name = 'idx_restock_runs_branch_status'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_runs_branch_status ON restock_runs (branch_id, status)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. restock_suggestions ------------------------------------------------------
CREATE TABLE IF NOT EXISTS restock_suggestions (
    id                CHAR(36)       NOT NULL PRIMARY KEY,
    run_id            CHAR(36)       NOT NULL,
    business_id       CHAR(36)       NOT NULL,
    branch_id         CHAR(36)       NOT NULL,
    item_id           CHAR(36)       NOT NULL,
    supplier_id       CHAR(36)       NULL,
    target            VARCHAR(8)     NOT NULL,
    on_hand           DECIMAL(14,4)  NOT NULL DEFAULT 0,
    inbound           DECIMAL(14,4)  NOT NULL DEFAULT 0,
    reorder_level     DECIMAL(14,4)  NULL,
    par               DECIMAL(14,4)  NOT NULL DEFAULT 0,
    suggested_qty     DECIMAL(14,4)  NOT NULL DEFAULT 0,
    accepted_qty      DECIMAL(14,4)  NULL,
    unit_cost         DECIMAL(14,4)  NULL,
    pack_size         DECIMAL(14,4)  NULL,
    lead_time_days    INT            NULL,
    reason_code       VARCHAR(64)    NOT NULL,
    evidence          VARCHAR(255)   NOT NULL,
    confidence        VARCHAR(16)    NOT NULL,
    status            VARCHAR(16)    NOT NULL DEFAULT 'pending',
    snooze_until      DATE           NULL,
    purchase_order_id CHAR(36)       NULL,
    order_pad_item_id CHAR(36)       NULL,
    created_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_restock_suggestion_run_item UNIQUE (run_id, item_id),
    CONSTRAINT fk_restock_sugg_run     FOREIGN KEY (run_id)      REFERENCES restock_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_restock_sugg_item    FOREIGN KEY (item_id)     REFERENCES items (id),
    CONSTRAINT fk_restock_sugg_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'restock_suggestions'
     AND index_name = 'idx_restock_sugg_run'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_sugg_run ON restock_suggestions (run_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'restock_suggestions'
     AND index_name = 'idx_restock_sugg_branch_status'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_sugg_branch_status ON restock_suggestions (business_id, branch_id, status)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'restock_suggestions'
     AND index_name = 'idx_restock_sugg_item'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_sugg_item ON restock_suggestions (item_id)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
