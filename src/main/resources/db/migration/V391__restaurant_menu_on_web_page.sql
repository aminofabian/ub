-- A restaurant may show its menu on its public web page (the shop's own address, nobody signed in).
-- Off until an owner switches it on: prices are not published without being asked.
ALTER TABLE restaurant_settings
  ADD COLUMN menu_online BOOLEAN NOT NULL DEFAULT FALSE;
