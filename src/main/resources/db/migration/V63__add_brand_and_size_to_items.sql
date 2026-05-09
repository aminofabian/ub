ALTER TABLE items
    ADD COLUMN brand VARCHAR(255) NULL AFTER legacy_import_source_id,
    ADD COLUMN size  VARCHAR(50)  NULL AFTER brand;
