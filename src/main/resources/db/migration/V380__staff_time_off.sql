-- When a staff member cannot be booked: a day off, leave, a long break (ADR 0016, calendars).
-- A short list per person, kept on the profile as "fromEpochSecond:toEpochSecond:reason;..."
-- rather than a table: it is read whole with the profile and is never queried by time.
-- Nullable, so every existing profile is unchanged.

ALTER TABLE staff_profiles
  ADD COLUMN time_off VARCHAR(2000) NULL;
