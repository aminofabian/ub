-- Named menus: a serving window on a heading (Breakfast 06:00-11:00, Lunch 11:00-16:00). The
-- heading's name is the menu's name. A dish shows only when both its heading's window and its
-- own window are open. Minutes after midnight, Nairobi time; an end before the start runs past
-- midnight. Null on both means the heading is served all day, so nothing changes for anyone
-- until an owner sets hours on a heading.
ALTER TABLE menu_categories
  ADD COLUMN serve_from_minute INT NULL,
  ADD COLUMN serve_to_minute   INT NULL;
