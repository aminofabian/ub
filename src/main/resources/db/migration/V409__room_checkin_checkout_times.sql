-- ADR 0031: check-in and check-out times are configured, not hardcoded. A room type may set its own
-- (a deluxe room checking out later than a standard); the business default sits on restaurant_settings
-- beside the other per-business switches; both fall back in code to 14:00 in / 11:00 out. Nullable TIME
-- columns, no backfill (null means "use the default, then the code fallback"). Nights are still counted
-- by calendar date (ADR 0028) — the time is only the hour stamped on the stay's instants.
ALTER TABLE room_types
    ADD COLUMN check_in_time  TIME NULL,
    ADD COLUMN check_out_time TIME NULL;

ALTER TABLE restaurant_settings
    ADD COLUMN check_in_time  TIME NULL,
    ADD COLUMN check_out_time TIME NULL;
