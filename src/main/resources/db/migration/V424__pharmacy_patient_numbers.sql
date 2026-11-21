-- Pharmacy / clinic Phase 0 (ADR 0034, decision 10): a patient number per business.
--
-- Several people can share one phone number (a parent and children), so a phone alone cannot
-- name a patient. patient_no is a small running number per business, shown as "P-0412".
--
--  * pharmacy_patients.patient_no: nullable so the column add is instant; every existing row is
--    numbered below in the order it was created, and every new row is numbered by the service.
--  * uq_ppt_business_no: one number per business. Removed (soft-deleted) profiles keep theirs,
--    so a number is never handed to a second person.
--
-- Additive. Idempotent: a re-run numbers only rows that still have no number, continuing from
-- the highest number the business already has. pharmacy_patients is a small table.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_patients' AND COLUMN_NAME = 'patient_no') = 0,
  'ALTER TABLE pharmacy_patients ADD COLUMN patient_no BIGINT NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE pharmacy_patients p
  JOIN (SELECT id,
               ROW_NUMBER() OVER (PARTITION BY business_id ORDER BY created_at, id) AS rn
          FROM pharmacy_patients
         WHERE patient_no IS NULL) n ON n.id = p.id
  JOIN (SELECT business_id, COALESCE(MAX(patient_no), 0) AS top
          FROM pharmacy_patients
         GROUP BY business_id) m ON m.business_id = p.business_id
   SET p.patient_no = m.top + n.rn
 WHERE p.patient_no IS NULL;

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_patients' AND INDEX_NAME = 'uq_ppt_business_no') = 0,
  'ALTER TABLE pharmacy_patients ADD UNIQUE KEY uq_ppt_business_no (business_id, patient_no)',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
