-- A restaurant's menu is shown on its web page unless the owner switches it off. New rows
-- take the new default; rows that already exist keep what their owner chose.
ALTER TABLE restaurant_settings
  ALTER COLUMN menu_online SET DEFAULT TRUE;
