-- Supplier engagement, first slice (docs/plans/SUPPLIER_ENGAGEMENT_DESIGN.md §12).
-- When each order was answered, dispatched and, if cancelled, the status it was cancelled from.
-- Nullable and not backfilled: standing starts from the day these exist.
ALTER TABLE sales_orders
  ADD COLUMN answered_at     TIMESTAMP   NULL,
  ADD COLUMN dispatched_at   TIMESTAMP   NULL,
  ADD COLUMN cancelled_from  VARCHAR(24) NULL;
