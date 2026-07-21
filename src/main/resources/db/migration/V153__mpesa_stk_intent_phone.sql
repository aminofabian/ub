-- Phone the STK prompt was sent to (may differ from the account's directory phone).
ALTER TABLE mpesa_stk_intents
  ADD COLUMN stk_phone VARCHAR(32) NULL AFTER amount;
