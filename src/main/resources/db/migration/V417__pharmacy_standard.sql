-- Pharmacy Phase 2 — "Pharmacy Standard" (ADR 0033, Phase 2 section).
--
--  * pharmacy_patients: a patient profile a pharmacy keeps for repeat customers. Health data:
--    read only with pharmacy.records.read, every read audited.
--  * dispensing_records.patient_id: links a record to a profile (the typed name and phone stay
--    on the record as a snapshot).
--  * dispensing_record_lines.quantity_prescribed / settles_line_id: partial supply. A line that
--    supplied less than was prescribed leaves a balance; a later line settles it.
--  * dispensing_record_lines.refill_due_on: when the supply runs out, for the refills-due list.
--  * pharmacy_temperature_logs: storage temperature readings, append-only.
--  * pharmacy_supplier_licences: a supplier's wholesale licence as the shop recorded it.
--  * pharmacy_adverse_events: notes on suspected adverse reactions and quality complaints.
--
-- Additive only, no backfill. Idempotent: MySQL/MariaDB DDL auto-commits, so a partial apply
-- must be safely re-runnable. New nullable columns are instant on MySQL 8.4 / MariaDB 10.11;
-- the three index builds scan dispensing_records / dispensing_record_lines once (small tables).

CREATE TABLE IF NOT EXISTS pharmacy_patients (
  id                   CHAR(36)      NOT NULL PRIMARY KEY,
  business_id          CHAR(36)      NOT NULL,
  name                 VARCHAR(191)  NOT NULL,
  phone                VARCHAR(32)   NULL,
  date_of_birth        DATE          NULL,
  allergies            VARCHAR(500)  NULL,
  notes                VARCHAR(1000) NULL,
  reminder_consent     BOOLEAN       NOT NULL DEFAULT FALSE,
  reminder_consent_at  TIMESTAMP     NULL,
  created_by           CHAR(36)      NULL,
  version              BIGINT        NOT NULL DEFAULT 0,
  created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at           TIMESTAMP     NULL,
  CONSTRAINT fk_ppt_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_ppt_business_phone (business_id, phone),
  KEY idx_ppt_business_name (business_id, name)
);

CREATE TABLE IF NOT EXISTS pharmacy_temperature_logs (
  id            CHAR(36)     NOT NULL PRIMARY KEY,
  business_id   CHAR(36)     NOT NULL,
  branch_id     CHAR(36)     NOT NULL,
  storage_name  VARCHAR(64)  NOT NULL,
  reading_c     DECIMAL(5,2) NOT NULL,
  min_c         DECIMAL(5,2) NOT NULL,
  max_c         DECIMAL(5,2) NOT NULL,
  out_of_range  BOOLEAN      NOT NULL DEFAULT FALSE,
  action_note   VARCHAR(255) NULL,
  recorded_by   CHAR(36)     NOT NULL,
  recorded_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ptl_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_ptl_business_branch_time (business_id, branch_id, recorded_at)
);

CREATE TABLE IF NOT EXISTS pharmacy_supplier_licences (
  id           CHAR(36)     NOT NULL PRIMARY KEY,
  business_id  CHAR(36)     NOT NULL,
  supplier_id  CHAR(36)     NOT NULL,
  licence_no   VARCHAR(64)  NULL,
  expires_on   DATE         NULL,
  note         VARCHAR(255) NULL,
  updated_by   CHAR(36)     NULL,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_psl_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_psl_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id),
  UNIQUE KEY uq_psl_business_supplier (business_id, supplier_id)
);

CREATE TABLE IF NOT EXISTS pharmacy_adverse_events (
  id                   CHAR(36)      NOT NULL PRIMARY KEY,
  business_id          CHAR(36)      NOT NULL,
  occurred_on          DATE          NOT NULL,
  item_id              CHAR(36)      NULL,
  product_name         VARCHAR(255)  NOT NULL,
  lot_number           VARCHAR(64)   NULL,
  patient_id           CHAR(36)      NULL,
  description          VARCHAR(2000) NOT NULL,
  severity             VARCHAR(16)   NOT NULL,
  action_taken         VARCHAR(500)  NULL,
  reported_externally  BOOLEAN       NOT NULL DEFAULT FALSE,
  external_ref         VARCHAR(64)   NULL,
  reported_at          TIMESTAMP     NULL,
  recorded_by          CHAR(36)      NOT NULL,
  created_at           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pae_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_pae_business_time (business_id, created_at)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_records' AND COLUMN_NAME = 'patient_id') = 0,
  'ALTER TABLE dispensing_records ADD COLUMN patient_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND COLUMN_NAME = 'quantity_prescribed') = 0,
  'ALTER TABLE dispensing_record_lines ADD COLUMN quantity_prescribed DECIMAL(14,4) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND COLUMN_NAME = 'refill_due_on') = 0,
  'ALTER TABLE dispensing_record_lines ADD COLUMN refill_due_on DATE NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND COLUMN_NAME = 'settles_line_id') = 0,
  'ALTER TABLE dispensing_record_lines ADD COLUMN settles_line_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_records' AND INDEX_NAME = 'idx_dr_business_patient') = 0,
  'CREATE INDEX idx_dr_business_patient ON dispensing_records (business_id, patient_id)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND INDEX_NAME = 'idx_drl_business_refill') = 0,
  'CREATE INDEX idx_drl_business_refill ON dispensing_record_lines (business_id, refill_due_on)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_record_lines' AND INDEX_NAME = 'idx_drl_business_settles') = 0,
  'CREATE INDEX idx_drl_business_settles ON dispensing_record_lines (business_id, settles_line_id)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
