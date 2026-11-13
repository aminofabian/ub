-- Shops can turn shifts off (feature flag shifts.disabled). Every sale still needs a shift row
-- (sales.shift_id is NOT NULL, and drawer, drawout and sync tables hang off it), so those shops
-- get one hidden, system-managed shift per till that opens on the first sale. This column marks
-- it, so it can be closed cleanly when shifts are switched back on and is never confused with a
-- shift a cashier opened.
--
-- shifts holds roughly one row per till per trading day, so a plain ADD COLUMN with a constant
-- default is safe here (no table rewrite on MySQL 8.4 / MariaDB 10.11).
ALTER TABLE shifts
  ADD COLUMN auto_managed BOOLEAN NOT NULL DEFAULT FALSE AFTER blind_closing;
