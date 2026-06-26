-- Provenance link from tenant items to the global catalog template they were adopted from.

ALTER TABLE items
  ADD COLUMN global_product_source_id CHAR(36) NULL,
  ADD INDEX idx_items_global_source (business_id, global_product_source_id),
  ADD CONSTRAINT fk_items_global_product_source
      FOREIGN KEY (global_product_source_id) REFERENCES global_products(id);
