-- Distinguish wallet top-up STK intents from customer tab (AR) paydowns.
ALTER TABLE mpesa_stk_intents
  ADD COLUMN purpose VARCHAR(16) NOT NULL DEFAULT 'wallet' AFTER credit_account_id;

UPDATE mpesa_stk_intents SET purpose = 'wallet' WHERE purpose IS NULL OR purpose = '';
