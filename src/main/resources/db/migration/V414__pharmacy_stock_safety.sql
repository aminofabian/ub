-- Pharmacy Phase 1a — stock safety (ADR 0033).
--
--  * item_medicine_details: marks an item as a medicine and carries its attributes and
--    dispensing class. One row per medicine item; absent for everything else.
--  * inventory_batches.lot_number: the manufacturer's lot printed on the pack. batch_number
--    stays the system identifier (it is generated, so a recall notice cannot be matched to it).
--  * inventory_batches.quarantine_*: why and when a batch was taken off sale. The status value
--    'quarantined' needs no DDL (status is VARCHAR(16)).
--  * raw_purchase_lines.draft_lot_number: lot typed on a supplies-drawer (Path B) draft line.
--
-- Additive only, no backfill. Idempotent: MySQL/MariaDB DDL auto-commits, so a partial apply
-- must be safely re-runnable. Adding nullable columns is an instant/online change on MySQL 8.4
-- and MariaDB 10.11; the lot index is built online but scans inventory_batches once.

CREATE TABLE IF NOT EXISTS item_medicine_details (
  item_id            CHAR(36)     NOT NULL PRIMARY KEY,
  business_id        CHAR(36)     NOT NULL,
  generic_name       VARCHAR(255) NULL,
  strength           VARCHAR(64)  NULL,
  dosage_form        VARCHAR(64)  NULL,
  pack_size          VARCHAR(64)  NULL,
  manufacturer       VARCHAR(191) NULL,
  registration_no    VARCHAR(64)  NULL,
  storage_note       VARCHAR(255) NULL,
  dispensing_class   VARCHAR(24)  NOT NULL DEFAULT 'UNCLASSIFIED',
  restock_on_refund  BOOLEAN      NOT NULL DEFAULT FALSE,
  class_set_by       CHAR(36)     NULL,
  class_set_at       TIMESTAMP    NULL,
  version            BIGINT       NOT NULL DEFAULT 0,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_imd_item FOREIGN KEY (item_id) REFERENCES items (id),
  CONSTRAINT fk_imd_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_imd_business_class (business_id, dispensing_class),
  KEY idx_imd_business_generic (business_id, generic_name)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_batches' AND COLUMN_NAME = 'lot_number') = 0,
  'ALTER TABLE inventory_batches ADD COLUMN lot_number VARCHAR(64) NULL AFTER batch_number',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_batches' AND COLUMN_NAME = 'quarantine_reason') = 0,
  'ALTER TABLE inventory_batches ADD COLUMN quarantine_reason VARCHAR(255) NULL AFTER status',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_batches' AND COLUMN_NAME = 'quarantined_at') = 0,
  'ALTER TABLE inventory_batches ADD COLUMN quarantined_at TIMESTAMP NULL AFTER quarantine_reason',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_batches' AND COLUMN_NAME = 'quarantined_by') = 0,
  'ALTER TABLE inventory_batches ADD COLUMN quarantined_by CHAR(36) NULL AFTER quarantined_at',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'inventory_batches' AND INDEX_NAME = 'idx_ib_business_lot') = 0,
  'CREATE INDEX idx_ib_business_lot ON inventory_batches (business_id, lot_number)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'raw_purchase_lines' AND COLUMN_NAME = 'draft_lot_number') = 0,
  'ALTER TABLE raw_purchase_lines ADD COLUMN draft_lot_number VARCHAR(64) NULL AFTER draft_expiry_date',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- pharmacy.manage: mark items as medicines, set their dispensing class, edit the pharmacy profile.
INSERT INTO permissions (id, permission_key, description)
SELECT '7c1e5a10-3b7d-4f1a-9a51-6d0f6a2e4101', 'pharmacy.manage',
       'Mark products as medicines, set their dispensing class, and manage pharmacy settings.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'pharmacy.manage');

-- Owner, Admin, Manager.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000001', p.id FROM permissions p WHERE p.permission_key = 'pharmacy.manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000002', p.id FROM permissions p WHERE p.permission_key = 'pharmacy.manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000003', p.id FROM permissions p WHERE p.permission_key = 'pharmacy.manage';
