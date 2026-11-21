-- Clinic (ADR 0034): a visit is a held service ticket with a patient on it; the prescription
-- is its own record; a scheme can pay for a service.
--
--  * clinic_settings: one row per business that has switched Clinic on. Backend rules key off
--    this row, never off the shop format.
--  * pos_drafts.patient_id: who is being treated (the ticket's customer stays "who pays").
--    A ticket with a patient is health data.
--  * pos_drafts.review_of_sale_id: a review visit points at the earlier visit's sale.
--  * pos_draft_lines.given_here: a medicine given in the clinic (injection, nebuliser). Still a
--    product line: sold, lot-picked and class-checked like any other.
--  * pharmacy_patients.customer_id: who usually pays for this patient.
--  * prescriptions / prescription_lines: what a clinician prescribed on a visit. No diagnosis.
--    Health data: read under the dispensing-record rule, every read audited.
--  * dispensing_records.prescription_id: the prescription a record was dispensed from.
--  * pharmacy_scheme_claims.record_id becomes nullable: a claim for a consultation with no
--    medicines stands on the sale alone.
--
-- Additive, no backfill, except the one relaxation of record_id from NOT NULL to NULL (existing
-- rows keep their value). Idempotent. New nullable columns are instant on MySQL 8.4 and MariaDB
-- 10.11; given_here has a constant default, which is also instant on both.

CREATE TABLE IF NOT EXISTS clinic_settings (
  business_id               CHAR(36)      NOT NULL PRIMARY KEY,
  enabled                   BOOLEAN       NOT NULL DEFAULT TRUE,
  fee_first                 BOOLEAN       NOT NULL DEFAULT FALSE,
  free_review_days          INT           NOT NULL DEFAULT 0,
  consultation_service_id   CHAR(36)      NULL,
  review_service_id         CHAR(36)      NULL,
  perform_service_ids       VARCHAR(4000) NULL,
  second_person_for_gated   BOOLEAN       NOT NULL DEFAULT FALSE,
  version                   BIGINT        NOT NULL DEFAULT 0,
  created_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_clinic_settings_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pos_drafts' AND COLUMN_NAME = 'patient_id') = 0,
  'ALTER TABLE pos_drafts ADD COLUMN patient_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pos_drafts' AND COLUMN_NAME = 'review_of_sale_id') = 0,
  'ALTER TABLE pos_drafts ADD COLUMN review_of_sale_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pos_drafts' AND INDEX_NAME = 'idx_pos_drafts_patient') = 0,
  'ALTER TABLE pos_drafts ADD KEY idx_pos_drafts_patient (business_id, patient_id, created_at)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pos_draft_lines' AND COLUMN_NAME = 'given_here') = 0,
  'ALTER TABLE pos_draft_lines ADD COLUMN given_here BOOLEAN NOT NULL DEFAULT FALSE',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_patients' AND COLUMN_NAME = 'customer_id') = 0,
  'ALTER TABLE pharmacy_patients ADD COLUMN customer_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS prescriptions (
  id                  CHAR(36)     NOT NULL PRIMARY KEY,
  business_id         CHAR(36)     NOT NULL,
  branch_id           CHAR(36)     NOT NULL,
  visit_id            CHAR(36)     NOT NULL,
  patient_id          CHAR(36)     NOT NULL,
  prescriber_user_id  CHAR(36)     NOT NULL,
  prescriber_name     VARCHAR(191) NOT NULL,
  prescriber_reg_no   VARCHAR(64)  NULL,
  prescribed_on       DATE         NOT NULL,
  status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
  prepared_by         CHAR(36)     NULL,
  prepared_at         TIMESTAMP    NULL,
  sale_id             CHAR(36)     NULL,
  supersedes_id       CHAR(36)     NULL,
  version             BIGINT       NOT NULL DEFAULT 0,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_prescriptions_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_prescriptions_visit (business_id, visit_id),
  KEY idx_prescriptions_patient (business_id, patient_id, created_at)
);

CREATE TABLE IF NOT EXISTS prescription_lines (
  id               CHAR(36)      NOT NULL PRIMARY KEY,
  business_id      CHAR(36)      NOT NULL,
  prescription_id  CHAR(36)      NOT NULL,
  line_index       INT           NOT NULL,
  item_id          CHAR(36)      NULL,
  medicine_name    VARCHAR(255)  NOT NULL,
  quantity         DECIMAL(14,4) NOT NULL,
  instructions     VARCHAR(500)  NULL,
  created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_prescription_lines_rx FOREIGN KEY (prescription_id) REFERENCES prescriptions (id),
  KEY idx_prescription_lines_rx (business_id, prescription_id, line_index)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_records' AND COLUMN_NAME = 'prescription_id') = 0,
  'ALTER TABLE dispensing_records ADD COLUMN prescription_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_scheme_claims'
      AND COLUMN_NAME = 'record_id' AND IS_NULLABLE = 'NO') = 1,
  'ALTER TABLE pharmacy_scheme_claims MODIFY COLUMN record_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
