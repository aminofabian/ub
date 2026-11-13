-- Chairs / stations / rooms of a service business (ADR 0014 amendment, ticket T15).
--
-- No new table: chairs are numbered 1..N per branch, so a count on branches is enough, with the
-- chair a staff member usually works at on their profile. Both tables are small.
-- 0 means the branch has no chairs set up (every retail branch).

ALTER TABLE branches
  ADD COLUMN service_station_count INT NOT NULL DEFAULT 0;

ALTER TABLE staff_profiles
  ADD COLUMN usual_station_no INT NULL;
