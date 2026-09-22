-- M-Pesa phone used for Daraja Express STK (Profit Pocket test / pocket).
ALTER TABLE profit_pocket_settings
  ADD COLUMN stk_phone VARCHAR(32) NULL AFTER send_rail;
