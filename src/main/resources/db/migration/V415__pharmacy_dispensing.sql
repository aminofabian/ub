-- Pharmacy Phase 1b — dispensing (ADR 0033).
--
--  * dispensing_records / dispensing_record_lines: who supplied which gated medicine, on whose
--    prescription, in which sale. Append-only: a correction is a new row (supersedes_id), a
--    voided sale stamps voided_at and keeps the row. Batches and lots are read through the
--    sale items, not copied here.
--  * dispensing_approvals: a pharmacist approved a cashier's sale with their PIN. The sale
--    request carries only this id, so a PIN never sits in an offline sale queue.
--  * Permissions pharmacy.dispense and pharmacy.records.read, and a Pharmacist system role.
--
-- Patient name and phone are optional and are health-related personal data: keep them out of
-- logs, exports and message text. New tables only; nothing existing is altered.

CREATE TABLE IF NOT EXISTS dispensing_records (
  id                  CHAR(36)     NOT NULL PRIMARY KEY,
  business_id         CHAR(36)     NOT NULL,
  branch_id           CHAR(36)     NOT NULL,
  sale_id             CHAR(36)     NOT NULL,
  dispensed_by        CHAR(36)     NOT NULL,
  sold_by             CHAR(36)     NULL,
  approval_id         CHAR(36)     NULL,
  prescriber_name     VARCHAR(191) NOT NULL,
  prescriber_reg_no   VARCHAR(64)  NULL,
  prescription_ref    VARCHAR(64)  NULL,
  prescription_date   DATE         NULL,
  patient_name        VARCHAR(191) NULL,
  patient_phone       VARCHAR(32)  NULL,
  supersedes_id       CHAR(36)     NULL,
  voided_at           TIMESTAMP    NULL,
  voided_by           CHAR(36)     NULL,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_dr_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_dr_sale FOREIGN KEY (sale_id) REFERENCES sales (id),
  KEY idx_dr_business_created (business_id, created_at),
  KEY idx_dr_business_sale (business_id, sale_id)
);

CREATE TABLE IF NOT EXISTS dispensing_record_lines (
  id                CHAR(36)      NOT NULL PRIMARY KEY,
  record_id         CHAR(36)      NOT NULL,
  business_id       CHAR(36)      NOT NULL,
  item_id           CHAR(36)      NOT NULL,
  item_name         VARCHAR(255)  NOT NULL,
  dispensing_class  VARCHAR(24)   NOT NULL,
  quantity          DECIMAL(14,4) NOT NULL,
  instructions      VARCHAR(500)  NULL,
  sale_item_ids     VARCHAR(1000) NULL,
  lot_numbers       VARCHAR(500)  NULL,
  created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_drl_record FOREIGN KEY (record_id) REFERENCES dispensing_records (id),
  KEY idx_drl_record (record_id),
  KEY idx_drl_business_item (business_id, item_id)
);

CREATE TABLE IF NOT EXISTS dispensing_approvals (
  id                 CHAR(36)   NOT NULL PRIMARY KEY,
  business_id        CHAR(36)   NOT NULL,
  approver_user_id   CHAR(36)   NOT NULL,
  requested_by       CHAR(36)   NOT NULL,
  item_ids           VARCHAR(2000) NULL,
  created_at         TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at         TIMESTAMP  NOT NULL,
  used_at            TIMESTAMP  NULL,
  used_sale_id       CHAR(36)   NULL,
  CONSTRAINT fk_da_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_da_business_expires (business_id, expires_at)
);

INSERT INTO permissions (id, permission_key, description)
SELECT '7c1e5a10-3b7d-4f1a-9a51-6d0f6a2e4102', 'pharmacy.dispense',
       'Dispense prescription-only and controlled medicines, and approve a cashier''s sale of them.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'pharmacy.dispense');

INSERT INTO permissions (id, permission_key, description)
SELECT '7c1e5a10-3b7d-4f1a-9a51-6d0f6a2e4103', 'pharmacy.records.read',
       'View dispensing records, including prescriber and patient details.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'pharmacy.records.read');

-- Pharmacist: everything a cashier can do, plus dispensing, the dispensing log and medicine set-up.
INSERT IGNORE INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-00000000000d', NULL, 'pharmacist', 'Pharmacist',
   'Sells at the till and dispenses prescription-only medicines. Can approve a cashier''s sale of them, classify medicines and read the dispensing log.',
   TRUE);

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000d', rp.permission_id
  FROM role_permissions rp
 WHERE rp.role_id = '22222222-0000-0000-0000-000000000004';

-- Owner and Admin: dispense + read records. Manager: read records. Pharmacist: all three pharmacy keys.
-- One statement per role, comparing the key to literals (a derived-table join can hit a
-- collation mismatch on some servers).
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000001', p.id
  FROM permissions p
 WHERE p.permission_key IN ('pharmacy.dispense', 'pharmacy.records.read');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000002', p.id
  FROM permissions p
 WHERE p.permission_key IN ('pharmacy.dispense', 'pharmacy.records.read');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000003', p.id
  FROM permissions p
 WHERE p.permission_key = 'pharmacy.records.read';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000d', p.id
  FROM permissions p
 WHERE p.permission_key IN ('pharmacy.dispense', 'pharmacy.records.read', 'pharmacy.manage');

-- Wrong-PIN attempts at an approval, kept to rate-limit guessing a pharmacist's PIN from a
-- cashier's till without touching the pharmacist's own sign-in lockout.
CREATE TABLE IF NOT EXISTS dispensing_approval_failures (
  id                CHAR(36)   NOT NULL PRIMARY KEY,
  business_id       CHAR(36)   NOT NULL,
  approver_user_id  CHAR(36)   NOT NULL,
  requested_by      CHAR(36)   NOT NULL,
  created_at        TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_daf_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_daf_lookup (business_id, approver_user_id, created_at),
  KEY idx_daf_requester (business_id, requested_by, created_at)
);
