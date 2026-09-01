-- Speed up "customers who bought product X" segmentation queries.
CREATE INDEX idx_sale_items_item_sale ON sale_items (item_id, sale_id);
