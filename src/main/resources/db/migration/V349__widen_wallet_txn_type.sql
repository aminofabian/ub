-- wallet_transactions.txn_type was VARCHAR(24), but two of its own values are longer:
--   'reversal_void_spend_restore' (27) and 'reversal_void_overpay_claw' (26).
-- Inserting them failed with a data-integrity error, so voiding any sale that had spent
-- wallet balance or carried overpay-to-wallet broke. Widen to fit the descriptive types.
ALTER TABLE wallet_transactions
    MODIFY COLUMN txn_type VARCHAR(40) NOT NULL;
