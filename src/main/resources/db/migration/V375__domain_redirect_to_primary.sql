-- A replaced free address (king-kong.kiosk.ke after a move to kingkong.kiosk.ke)
-- sends visitors to the shop's current main address. The path and query stay.
-- Custom domains are left alone; the owner turns those on from Domains.
-- Addresses already replaced get the redirect, so a change made before this
-- column existed starts sending people on as well.

ALTER TABLE domains
  ADD COLUMN redirect_to_primary BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE domains
   SET redirect_to_primary = TRUE
 WHERE source = 'PLATFORM_SUBDOMAIN'
   AND is_primary = FALSE
   AND active = TRUE
   AND deleted_at IS NULL;
