-- Pharmacy: products added since the pharmacist last checked.
--
-- Anyone who can add a product can add a medicine as an ordinary product, by mistake or to get a
-- sale through; it then sells with no class, lot or expiry rule. The Medicines page lists
-- products added since products_reviewed_at that are not marked as medicines, and whoever
-- manages the pharmacy marks the ones that are and confirms the rest are not. NULL means never
-- checked: the list then covers the last 30 days.
--
-- Additive, nullable, no backfill. Idempotent.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_profiles' AND COLUMN_NAME = 'products_reviewed_at') = 0,
  'ALTER TABLE pharmacy_profiles
     ADD COLUMN products_reviewed_at TIMESTAMP NULL,
     ADD COLUMN products_reviewed_by CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
