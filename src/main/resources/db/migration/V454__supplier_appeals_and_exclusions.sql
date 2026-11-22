-- Supplier appeals and platform exclusion from standing (reversible). Off by default: no code path sets these
-- unless the supplier_engagement.standing flag is on and platform support excludes an order.
ALTER TABLE sales_orders
    ADD standing_excluded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD standing_excluded_at TIMESTAMP NULL,
    ADD standing_exclusion_note VARCHAR(500) NULL;
