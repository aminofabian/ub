-- ADR 0027: a restaurant table may have a waiter who looks after it. A new order at the table is
-- theirs unless another is named. Nullable, no backfill: a table with none behaves as before
-- (whoever seats it serves it). service_resources is a small table; this is a plain ADD COLUMN.
ALTER TABLE service_resources
    ADD COLUMN assigned_staff_profile_id VARCHAR(36) NULL;
