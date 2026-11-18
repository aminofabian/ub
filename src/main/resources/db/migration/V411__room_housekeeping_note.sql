-- Hospitality Phase 2 (ADR 0030): a room carries a short housekeeping note — damage found, an
-- amenity missing, "needs a deep clean" — set by whoever turns it round, cleared when it is dealt
-- with. The cleaner responsible reuses the existing assigned_staff_profile_id column (the same slot
-- a table's waiter uses), so only the note is new. Nullable; no backfill.
ALTER TABLE service_resources
    ADD COLUMN housekeeping_note VARCHAR(300) NULL;
