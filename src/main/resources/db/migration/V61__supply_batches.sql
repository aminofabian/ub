-- SupplyBatch header entity — groups multiple InventoryBatch rows
-- that arrived together in one delivery / purchase trip.

CREATE TABLE supply_batches (
  id                      CHAR(36)       PRIMARY KEY,
  business_id             CHAR(36)       NOT NULL,
  branch_id               CHAR(36)       NOT NULL,
  supplier_id             CHAR(36)       NULL,
  batch_number            VARCHAR(64)    NOT NULL,
  batch_name              VARCHAR(255)   NULL,
  source_type             VARCHAR(32)    NOT NULL,
  source_id               CHAR(36)       NOT NULL,
  item_count              INT            NOT NULL DEFAULT 0,
  total_initial_quantity  DECIMAL(18,4)  NOT NULL DEFAULT 0,
  total_remaining_quantity DECIMAL(18,4) NOT NULL DEFAULT 0,
  received_at             TIMESTAMP      NOT NULL,
  status                  VARCHAR(16)    NOT NULL DEFAULT 'active',
  version                 BIGINT         NOT NULL DEFAULT 0,
  created_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at              TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_sb_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_sb_branch FOREIGN KEY (branch_id) REFERENCES branches (id),
  CONSTRAINT fk_sb_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE INDEX idx_sb_business_status ON supply_batches (business_id, status, received_at);
CREATE INDEX idx_sb_source ON supply_batches (source_type, source_id);
CREATE INDEX idx_sb_supplier ON supply_batches (business_id, supplier_id, status);

-- Add supply_batch_id to inventory_batches
ALTER TABLE inventory_batches
  ADD COLUMN supply_batch_id CHAR(36) NULL AFTER item_id,
  ADD CONSTRAINT fk_ib_supply_batch FOREIGN KEY (supply_batch_id) REFERENCES supply_batches (id);

CREATE INDEX idx_ib_supply_batch ON inventory_batches (supply_batch_id);

-- ── Backfill: one SupplyBatch per unique (business_id, source_type, source_id) ──

-- Path A: Goods Receipts
INSERT INTO supply_batches (id, business_id, branch_id, supplier_id, batch_number, batch_name, source_type, source_id, item_count, total_initial_quantity, total_remaining_quantity, received_at, status, version, created_at, updated_at)
SELECT
    UUID() AS id,
    ib.business_id,
    ib.branch_id,
    ib.supplier_id,
    CONCAT('SB-A-', LEFT(ib.source_id, 8)) AS batch_number,
    NULL AS batch_name,
    ib.source_type,
    ib.source_id,
    COUNT(*) AS item_count,
    SUM(ib.initial_quantity) AS total_initial_quantity,
    SUM(ib.quantity_remaining) AS total_remaining_quantity,
    MIN(ib.received_at) AS received_at,
    'active' AS status,
    0 AS version,
    NOW() AS created_at,
    NOW() AS updated_at
FROM inventory_batches ib
WHERE ib.source_type = 'path_a_grn'
  AND ib.supply_batch_id IS NULL
GROUP BY ib.business_id, ib.source_type, ib.source_id, ib.branch_id, ib.supplier_id;

-- Path B: Raw Purchase Sessions
INSERT INTO supply_batches (id, business_id, branch_id, supplier_id, batch_number, batch_name, source_type, source_id, item_count, total_initial_quantity, total_remaining_quantity, received_at, status, version, created_at, updated_at)
SELECT
    UUID() AS id,
    ib.business_id,
    ib.branch_id,
    ib.supplier_id,
    CONCAT('SB-B-', LEFT(ib.source_id, 8)) AS batch_number,
    NULL AS batch_name,
    ib.source_type,
    ib.source_id,
    COUNT(*) AS item_count,
    SUM(ib.initial_quantity) AS total_initial_quantity,
    SUM(ib.quantity_remaining) AS total_remaining_quantity,
    MIN(ib.received_at) AS received_at,
    'active' AS status,
    0 AS version,
    NOW() AS created_at,
    NOW() AS updated_at
FROM inventory_batches ib
WHERE ib.source_type = 'path_b_breakdown'
  AND ib.supply_batch_id IS NULL
GROUP BY ib.business_id, ib.source_type, ib.source_id, ib.branch_id, ib.supplier_id;

-- Path B wastage, opening balances, stock gains, transfers, refunds — one batch per inventory_batch row
INSERT INTO supply_batches (id, business_id, branch_id, supplier_id, batch_number, batch_name, source_type, source_id, item_count, total_initial_quantity, total_remaining_quantity, received_at, status, version, created_at, updated_at)
SELECT
    UUID() AS id,
    ib.business_id,
    ib.branch_id,
    ib.supplier_id,
    CONCAT('SB-SOLO-', LEFT(ib.id, 8)) AS batch_number,
    NULL AS batch_name,
    ib.source_type,
    ib.source_id,
    1 AS item_count,
    ib.initial_quantity AS total_initial_quantity,
    ib.quantity_remaining AS total_remaining_quantity,
    ib.received_at,
    'active' AS status,
    0 AS version,
    NOW() AS created_at,
    NOW() AS updated_at
FROM inventory_batches ib
WHERE ib.source_type NOT IN ('path_a_grn', 'path_b_breakdown')
  AND ib.supply_batch_id IS NULL;

-- ── Link inventory_batches to their supply_batch ─────────────────
UPDATE inventory_batches ib
JOIN supply_batches sb
  ON  sb.business_id = ib.business_id
  AND sb.source_type = ib.source_type
  AND sb.source_id   = ib.source_id
SET ib.supply_batch_id = sb.id
WHERE ib.supply_batch_id IS NULL;
