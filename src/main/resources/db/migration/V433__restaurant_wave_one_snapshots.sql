-- Wave 1 of the restaurant flow review (docs/scopes/RESTAURANT_FLOW_REVIEW.md).

-- 1. A kitchen ticket is a snapshot of what the kitchen was told. It now carries the table it is
--    for and whether the order is eaten in, taken away or delivered, so the pass can route it and
--    a cook can tell a plate from a bag. Its lines keep the seat they were ordered for.
ALTER TABLE kitchen_tickets
    ADD COLUMN order_channel VARCHAR(16)  NULL,
    ADD COLUMN table_name    VARCHAR(191) NULL;

ALTER TABLE kitchen_ticket_lines
    ADD COLUMN seat_no INT NULL;

-- 2. A printed order bill must not change after the customer has read it. The money each line was
--    charged is kept on the bill line instead of being recomputed from the order line's price.
ALTER TABLE order_bill_lines
    ADD COLUMN unit_price DECIMAL(14,4) NULL,
    ADD COLUMN amount     DECIMAL(14,2) NULL;

-- 3. A dish's tax rate is fixed when the dish is ordered. It used to be looked up again at payment,
--    so deleting or re-rating a dish mid-service changed (or dropped) the VAT on food already cooked.
ALTER TABLE pos_draft_lines
    ADD COLUMN tax_rate_id VARCHAR(36) NULL;

-- 4. Why an order was voided, moved, merged, comped or handed to another waiter, in words a
--    manager can read back.
ALTER TABLE pos_draft_audit_log
    ADD COLUMN detail VARCHAR(500) NULL;
