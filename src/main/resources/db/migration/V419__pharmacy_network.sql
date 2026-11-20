-- Pharmacy Phase 3 — ordering forecast settings, central purchasing, scheme claims (ADR 0033).
--
--  * pharmacy_profiles.order_lead_days / order_cover_days: how long a supplier takes to deliver and
--    how many days of stock an order should cover. Defaults 7 and 30.
--  * purchase_order_allocations: for an order raised centrally, how much of each line is meant for
--    each other branch. After the delivery is received, transfers are drafted from these rows.
--  * pharmacy_schemes: the medical schemes a shop deals with.
--  * pharmacy_scheme_claims: what a scheme owes for a dispensing record. Holds a member number,
--    which with the record is health data: read only with pharmacy.records.read.
--  * Permission pharmacy.claims.manage: add schemes, mark claims submitted, paid or rejected.
--
-- Additive only, no backfill. Idempotent.

CREATE TABLE IF NOT EXISTS purchase_order_allocations (
  id                 CHAR(36)      NOT NULL PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  purchase_order_id  CHAR(36)      NOT NULL,
  item_id            CHAR(36)      NOT NULL,
  branch_id          CHAR(36)      NOT NULL,
  quantity           DECIMAL(14,4) NOT NULL,
  transfer_id        CHAR(36)      NULL,
  created_by         CHAR(36)      NULL,
  created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_poa_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_poa_business_po (business_id, purchase_order_id),
  KEY idx_poa_business_open (business_id, transfer_id)
);

CREATE TABLE IF NOT EXISTS pharmacy_schemes (
  id                  CHAR(36)     NOT NULL PRIMARY KEY,
  business_id         CHAR(36)     NOT NULL,
  name                VARCHAR(191) NOT NULL,
  contact             VARCHAR(255) NULL,
  payment_terms_days  INT          NULL,
  active              BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_psch_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_psch_business (business_id, name)
);

CREATE TABLE IF NOT EXISTS pharmacy_scheme_claims (
  id                  CHAR(36)      NOT NULL PRIMARY KEY,
  business_id         CHAR(36)      NOT NULL,
  scheme_id           CHAR(36)      NOT NULL,
  record_id           CHAR(36)      NOT NULL,
  sale_id             CHAR(36)      NOT NULL,
  branch_id           CHAR(36)      NOT NULL,
  member_no           VARCHAR(64)   NOT NULL,
  authorisation_code  VARCHAR(64)   NULL,
  amount_claimed      DECIMAL(14,2) NOT NULL,
  amount_paid         DECIMAL(14,2) NOT NULL DEFAULT 0,
  status              VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
  status_note         VARCHAR(255)  NULL,
  submitted_at        TIMESTAMP     NULL,
  settled_at          TIMESTAMP     NULL,
  created_by          CHAR(36)      NOT NULL,
  created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pscl_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_pscl_scheme FOREIGN KEY (scheme_id) REFERENCES pharmacy_schemes (id),
  CONSTRAINT fk_pscl_record FOREIGN KEY (record_id) REFERENCES dispensing_records (id),
  KEY idx_pscl_business_status (business_id, status),
  KEY idx_pscl_business_record (business_id, record_id)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_profiles' AND COLUMN_NAME = 'order_lead_days') = 0,
  'ALTER TABLE pharmacy_profiles ADD COLUMN order_lead_days INT NOT NULL DEFAULT 7',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_profiles' AND COLUMN_NAME = 'order_cover_days') = 0,
  'ALTER TABLE pharmacy_profiles ADD COLUMN order_cover_days INT NOT NULL DEFAULT 30',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

INSERT INTO permissions (id, permission_key, description)
SELECT '7c1e5a10-3b7d-4f1a-9a51-6d0f6a2e4104', 'pharmacy.claims.manage',
       'Add medical schemes and mark scheme claims as submitted, paid or rejected.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'pharmacy.claims.manage');

-- Owner, Admin, Manager and Pharmacist. One statement per role (see V415 on collations).
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000001', p.id
  FROM permissions p
 WHERE p.permission_key = 'pharmacy.claims.manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000002', p.id
  FROM permissions p
 WHERE p.permission_key = 'pharmacy.claims.manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000003', p.id
  FROM permissions p
 WHERE p.permission_key = 'pharmacy.claims.manage';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000d', p.id
  FROM permissions p
 WHERE p.permission_key = 'pharmacy.claims.manage';
