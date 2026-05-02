-- Slice 3 — Time-windowed auth failures (PHASE_1_PLAN.md §3.4)

ALTER TABLE users
  ADD COLUMN auth_soft_window_start TIMESTAMP NULL AFTER locked_until,
  ADD COLUMN auth_hour_window_start TIMESTAMP NULL AFTER auth_soft_window_start,
  ADD COLUMN auth_hour_failures INT NOT NULL DEFAULT 0 AFTER auth_hour_window_start;
