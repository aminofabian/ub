-- ADR 0031: a lodging tenant publishes its rooms to the public storefront deliberately, as it
-- publishes its menu (menu_online). Default FALSE — unlike the menu (a restaurant page without its
-- menu is empty), a shop's rooms are shown online only when the owner turns it on. One row per
-- business on restaurant_settings. A plain NOT NULL add with a default; no backfill needed.
ALTER TABLE restaurant_settings
    ADD COLUMN rooms_online TINYINT(1) NOT NULL DEFAULT 0;
