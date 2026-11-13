-- Appointments, queue priority and staff floor status (service businesses, phases 1.5 and 2).
--
-- No new table. An appointment is a service ticket that has not arrived yet: the same
-- pos_drafts row with service_stage = 'BOOKED', a scheduled start and end, and who booked it.
-- When the customer arrives the stage becomes WAITING and the row joins the queue. All columns
-- are nullable or defaulted, so held carts and walk-in tickets are untouched.
--
-- The index serves "this staff member's bookings around a time" and the day calendar.
-- booking_name / booking_phone are customer details: keep them out of logs.

ALTER TABLE pos_drafts
  ADD COLUMN scheduled_start TIMESTAMP NULL,
  ADD COLUMN scheduled_end TIMESTAMP NULL,
  ADD COLUMN booking_name VARCHAR(191) NULL,
  ADD COLUMN booking_phone VARCHAR(32) NULL,
  ADD COLUMN booking_source VARCHAR(16) NULL,
  ADD COLUMN queue_priority INT NOT NULL DEFAULT 0,
  ADD KEY idx_pos_drafts_schedule (business_id, branch_id, scheduled_start);

-- Whether a staff member can take the next customer right now. Set by a person on the floor
-- board; it is not attendance or timekeeping.
ALTER TABLE staff_profiles
  ADD COLUMN floor_status VARCHAR(16) NULL;
