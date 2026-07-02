-- Scale PLU for variable-weight barcode labels (EAN-13 prefix 2 pattern).
ALTER TABLE items
    ADD COLUMN plu_code VARCHAR(16) NULL AFTER barcode;

CREATE UNIQUE INDEX uk_items_business_plu_code
    ON items (business_id, plu_code);
