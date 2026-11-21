-- Pharmacy — medicine details on the platform catalogue (ADR 0033, "Catalogue and till").
--
--  * global_product_medicine_details: one row per catalogue product that is a medicine. Mirrors
--    item_medicine_details: its presence marks the product, and adopting the product into a shop
--    marks the new item as a medicine with the same generic name, strength and form.
--    suggested_class is the catalogue editor's suggestion only. It never sets a shop's dispensing
--    class: the shop's pharmacist confirms it, and until then the medicine stays UNCLASSIFIED and
--    cannot be sold.
--  * item_medicine_details.suggested_class: the suggestion carried over on adoption, shown next to
--    an unclassified medicine so the pharmacist can confirm it.
--
-- One new table and one nullable column. Nothing existing is rewritten.

CREATE TABLE IF NOT EXISTS global_product_medicine_details (
  global_product_id  CHAR(36)     NOT NULL PRIMARY KEY,
  generic_name       VARCHAR(255) NULL,
  strength           VARCHAR(64)  NULL,
  dosage_form        VARCHAR(64)  NULL,
  pack_size          VARCHAR(64)  NULL,
  manufacturer       VARCHAR(191) NULL,
  suggested_class    VARCHAR(24)  NULL,
  version            BIGINT       NOT NULL DEFAULT 0,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_gpmd_product FOREIGN KEY (global_product_id) REFERENCES global_products (id) ON DELETE CASCADE,
  KEY idx_gpmd_generic (generic_name)
);

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'item_medicine_details' AND COLUMN_NAME = 'suggested_class') = 0,
  'ALTER TABLE item_medicine_details ADD COLUMN suggested_class VARCHAR(24) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
