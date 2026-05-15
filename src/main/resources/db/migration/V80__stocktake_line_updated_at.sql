ALTER TABLE stock_take_lines
  ADD COLUMN updated_at TIMESTAMP NULL
  AFTER confirmed_at;
