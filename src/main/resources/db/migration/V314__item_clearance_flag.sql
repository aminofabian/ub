-- Clearance SKUs may sell below cost even when Margin Guard is hard/approve.

ALTER TABLE items
  ADD COLUMN is_clearance BOOLEAN NOT NULL DEFAULT FALSE AFTER is_weighed;
