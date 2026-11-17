-- ADR 0028: a lodging room is a service_resource of type ROOM with a nightly rate. The rate is
-- frozen onto the room-night sale line at check-in, so changing it later never moves a live folio.
-- Null on every other kind of resource (chairs, tables, machines). service_resources is a small
-- table; this is a plain ADD COLUMN with no backfill.
ALTER TABLE service_resources
    ADD COLUMN nightly_rate DECIMAL(14,2) NULL;
