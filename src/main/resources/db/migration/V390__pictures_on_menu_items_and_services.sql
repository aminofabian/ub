-- A picture on a menu item and on a service, and a short description on a menu item
-- (ADR 0020 amendment 5; service pictures for the booking page and the shop's homepage).
--
-- The picture is stored the way a category's is: the address the media store gave back, kept on
-- the row, plus the store's own id so the old picture can be removed when it is replaced.
-- Both tables are small (a shop's menu, a shop's list of services); nullable columns only.
--
-- Forward fix: nothing to undo; a row with no picture shows none.

ALTER TABLE menu_items
  ADD COLUMN description     VARCHAR(500)  NULL,
  ADD COLUMN image_url       VARCHAR(2048) NULL,
  ADD COLUMN image_public_id VARCHAR(512)  NULL;

ALTER TABLE services
  ADD COLUMN image_url       VARCHAR(2048) NULL,
  ADD COLUMN image_public_id VARCHAR(512)  NULL;
