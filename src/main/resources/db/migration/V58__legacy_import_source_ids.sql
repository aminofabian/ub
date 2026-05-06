-- Maps legacy export UUIDs (e.g. old product_id / supplier_id) to Palmart rows for follow-on imports.

ALTER TABLE items
  ADD COLUMN legacy_import_source_id CHAR(36) NULL
  COMMENT 'Legacy catalog id from JSON export; used to resolve buying prices etc.';

CREATE UNIQUE INDEX uq_items_business_legacy_import
  ON items (business_id, legacy_import_source_id);

ALTER TABLE suppliers
  ADD COLUMN legacy_import_source_id CHAR(36) NULL
  COMMENT 'Legacy supplier id from JSON export; used to resolve buying prices etc.';

CREATE UNIQUE INDEX uq_suppliers_business_legacy_import
  ON suppliers (business_id, legacy_import_source_id);
