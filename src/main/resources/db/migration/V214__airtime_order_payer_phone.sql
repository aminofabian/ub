-- Paying MSISDN for an airtime sale (may differ from the line that receives).
ALTER TABLE airtime_orders
  ADD COLUMN payer_phone VARCHAR(32) NULL AFTER phone_number;
