-- Global sequential supplier numbers (S-000001…) + attach/promote settings.

CREATE TABLE platform_supplier_number_seq (
  id            CHAR(36)     NOT NULL PRIMARY KEY,
  next_value    BIGINT       NOT NULL,
  updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

INSERT INTO platform_supplier_number_seq (id, next_value)
VALUES ('00000000-0000-0000-0000-000000000001', 1);

ALTER TABLE marketplace_suppliers
  ADD COLUMN supplier_number VARCHAR(32) NULL AFTER id;

-- Backfill existing marketplace suppliers in creation order.
SET @supplier_seq := 0;
UPDATE marketplace_suppliers
SET supplier_number = CONCAT('S-', LPAD((@supplier_seq := @supplier_seq + 1), 6, '0'))
ORDER BY created_at ASC, id ASC;

UPDATE platform_supplier_number_seq
SET next_value = (
      SELECT COALESCE(MAX(CAST(SUBSTRING(supplier_number, 3) AS UNSIGNED)), 0) + 1
      FROM marketplace_suppliers
      WHERE supplier_number IS NOT NULL
    ),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE id = '00000000-0000-0000-0000-000000000001';
ALTER TABLE marketplace_suppliers
  MODIFY COLUMN supplier_number VARCHAR(32) NOT NULL;

CREATE UNIQUE INDEX uq_marketplace_suppliers_supplier_number
  ON marketplace_suppliers (supplier_number);

ALTER TABLE supplier_identity_index
  ADD COLUMN supplier_number_normalized VARCHAR(32) NULL AFTER tax_id_normalized;

CREATE INDEX idx_sii_supplier_number ON supplier_identity_index (supplier_number_normalized);

UPDATE supplier_identity_index sii
JOIN marketplace_suppliers ms ON ms.id = sii.marketplace_supplier_id
SET sii.supplier_number_normalized = ms.supplier_number
WHERE ms.supplier_number IS NOT NULL;

ALTER TABLE platform_supplier_portal_settings
  ADD COLUMN allow_find_unclaimed_drafts TINYINT(1) NOT NULL DEFAULT 1 AFTER allow_statement_downloads,
  ADD COLUMN auto_promote_on_create TINYINT(1) NOT NULL DEFAULT 1 AFTER allow_find_unclaimed_drafts;
