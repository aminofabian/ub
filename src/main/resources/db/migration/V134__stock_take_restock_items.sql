-- =============================================================================
-- V134 — Daily audit restock recommendations.
-- MySQL-compatible idempotent migration.
-- =============================================================================

CREATE TABLE IF NOT EXISTS stock_take_restock_items (
    id                    VARCHAR(36)   NOT NULL PRIMARY KEY,
    business_id           VARCHAR(36)   NOT NULL,
    branch_id             VARCHAR(36)   NOT NULL,
    daily_audit_id        VARCHAR(36),
    stock_take_session_id VARCHAR(36)   NOT NULL,
    stock_take_line_id    VARCHAR(36)   NOT NULL,
    item_id               VARCHAR(36)   NOT NULL,
    supplier_id           VARCHAR(36)   NOT NULL,
    suggested_qty         DECIMAL(14,4) NOT NULL,
    buying_price          DECIMAL(14,4),
    supplier_pack_size    DECIMAL(14,4),
    supplier_pack_unit    VARCHAR(32),
    note                  TEXT,
    status                VARCHAR(32)   NOT NULL DEFAULT 'pending',
    rejection_reason      TEXT,
    added_by              VARCHAR(36)   NOT NULL,
    added_at              TIMESTAMP(6)  NOT NULL,
    reviewed_by           VARCHAR(36),
    reviewed_at           TIMESTAMP(6),
    order_drafted_by      VARCHAR(36),
    order_drafted_at      TIMESTAMP(6),
    purchase_order_id     VARCHAR(36),
    order_number          VARCHAR(64),
    created_at            TIMESTAMP(6)  NOT NULL,
    updated_at            TIMESTAMP(6)  NOT NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_restock_pending UNIQUE (business_id, branch_id, daily_audit_id, item_id, supplier_id, status),
    CONSTRAINT fk_restock_daily_audit FOREIGN KEY (daily_audit_id) REFERENCES daily_stock_audits (id),
    CONSTRAINT fk_restock_session FOREIGN KEY (stock_take_session_id) REFERENCES stock_take_sessions (id),
    CONSTRAINT fk_restock_line FOREIGN KEY (stock_take_line_id) REFERENCES stock_take_lines (id),
    CONSTRAINT fk_restock_item FOREIGN KEY (item_id) REFERENCES items (id),
    CONSTRAINT fk_restock_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_business_branch_status'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_business_branch_status ON stock_take_restock_items (business_id, branch_id, status)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_business_daily_audit'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_business_daily_audit ON stock_take_restock_items (business_id, daily_audit_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_business_supplier_status'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_business_supplier_status ON stock_take_restock_items (business_id, supplier_id, status)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_session'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_session ON stock_take_restock_items (stock_take_session_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_line'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_line ON stock_take_restock_items (stock_take_line_id)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'stock_take_restock_items'
     AND index_name = 'idx_restock_order_number'
);
SET @sql := IF(@idx_exists = 0,
    'CREATE INDEX idx_restock_order_number ON stock_take_restock_items (business_id, order_number)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
