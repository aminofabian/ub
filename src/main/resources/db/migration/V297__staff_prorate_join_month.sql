-- Allow shops to turn off calendar-day join-month proration per staff member.
-- Default stays on (mid-month starts pay remaining days only).

ALTER TABLE staff_profiles
  ADD COLUMN prorate_join_month BOOLEAN NOT NULL DEFAULT TRUE;
