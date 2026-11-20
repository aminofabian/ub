-- Pharmacy — second check and correction notes on dispensing records (ADR 0033).
--
--  * pharmacy_profiles.second_check_enabled: the shop wants a second person who may dispense to
--    check each dispensing record. Off by default (most chemists have one pharmacist on site).
--  * dispensing_records.verified_by / verified_at: who checked the record, once. Like voided_at,
--    a stamp on the row; the record's contents never change.
--  * dispensing_record_corrections: a note that something on a record was written wrongly, and
--    what is right. Append-only. The record itself is never edited.
--
-- Additive only, no backfill. Idempotent. The new columns are nullable or defaulted, so they are
-- instant on MySQL 8.4 / MariaDB 10.11.

CREATE TABLE IF NOT EXISTS dispensing_record_corrections (
  id           CHAR(36)      NOT NULL PRIMARY KEY,
  business_id  CHAR(36)      NOT NULL,
  record_id    CHAR(36)      NOT NULL,
  note         VARCHAR(1000) NOT NULL,
  created_by   CHAR(36)      NOT NULL,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_drc_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_drc_record FOREIGN KEY (record_id) REFERENCES dispensing_records (id),
  KEY idx_drc_business_record (business_id, record_id)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_profiles' AND COLUMN_NAME = 'second_check_enabled') = 0,
  'ALTER TABLE pharmacy_profiles ADD COLUMN second_check_enabled BOOLEAN NOT NULL DEFAULT FALSE',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_records' AND COLUMN_NAME = 'verified_by') = 0,
  'ALTER TABLE dispensing_records ADD COLUMN verified_by CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispensing_records' AND COLUMN_NAME = 'verified_at') = 0,
  'ALTER TABLE dispensing_records ADD COLUMN verified_at TIMESTAMP NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
