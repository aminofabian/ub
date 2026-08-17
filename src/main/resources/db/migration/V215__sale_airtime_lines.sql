-- Airtime can ride on a POS sale with groceries. No catalog item / batch.
ALTER TABLE sale_items
    DROP FOREIGN KEY fk_si_item,
    DROP FOREIGN KEY fk_si_batch;

ALTER TABLE sale_items
    MODIFY item_id CHAR(36) NULL,
    MODIFY batch_id CHAR(36) NULL,
    ADD COLUMN line_kind VARCHAR(16) NOT NULL DEFAULT 'ITEM' AFTER line_index,
    ADD COLUMN line_label VARCHAR(255) NULL AFTER line_kind,
    ADD COLUMN airtime_phone VARCHAR(32) NULL AFTER line_label,
    ADD COLUMN airtime_network VARCHAR(16) NULL AFTER airtime_phone;

ALTER TABLE sale_items
    ADD CONSTRAINT fk_si_item FOREIGN KEY (item_id) REFERENCES items (id),
    ADD CONSTRAINT fk_si_batch FOREIGN KEY (batch_id) REFERENCES inventory_batches (id);
