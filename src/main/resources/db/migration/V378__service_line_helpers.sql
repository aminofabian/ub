-- Any number of staff can share one service line (a shave where one person washes, one cuts and
-- one finishes). Each helper and their share of the line is kept on the line itself as
-- "staffProfileId:percent;staffProfileId:percent"; the person named as performer keeps the rest.
--
-- This replaces the single assistant columns added in V376 (assistant_staff_profile_id,
-- assistant_share_percent), which are no longer read or written. They are left in place: nothing
-- is dropped without the owner's say-so.

ALTER TABLE sale_items
  ADD COLUMN service_helpers VARCHAR(400) NULL;

ALTER TABLE pos_draft_lines
  ADD COLUMN service_helpers VARCHAR(400) NULL;
