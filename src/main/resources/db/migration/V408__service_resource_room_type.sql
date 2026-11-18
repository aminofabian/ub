-- ADR 0030: a lodging room (service_resources of type ROOM) may belong to a room type and inherit
-- its rate card. Null on every room with no type and on every non-ROOM resource. A plain nullable
-- ADD COLUMN on a small table, no backfill; existing rooms keep their own nightly_rate.
ALTER TABLE service_resources
    ADD COLUMN room_type_id VARCHAR(36) NULL;

CREATE INDEX ix_service_resources_room_type ON service_resources (business_id, room_type_id);
