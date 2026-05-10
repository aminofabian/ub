-- Add is_default flag to item_types so users can mark one type as the preselected default.

ALTER TABLE item_types ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

CREATE INDEX idx_item_types_business_default ON item_types (business_id, is_default);
