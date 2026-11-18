-- ADR 0030: a lodging room has a housekeeping axis separate from occupancy and from `active`.
-- Occupancy comes from the stays on the room; `active` is the owner's "do not book" switch;
-- housekeeping_status is the cleaning cycle a cleaner or supervisor drives: CLEAN, DIRTY,
-- CLEANING, INSPECTED, MAINTENANCE. A room returns to bookable only once it is CLEAN (or
-- INSPECTED). Null on every non-ROOM resource (chairs, tables, machines); existing rooms default
-- to CLEAN so they stay bookable at deploy. service_resources is a small table; a plain
-- ADD COLUMN with no backfill.
ALTER TABLE service_resources
    ADD COLUMN housekeeping_status VARCHAR(16) NULL;
