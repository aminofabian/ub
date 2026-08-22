-- credit_transactions.sale_id is a polymorphic source id, not strictly a sales id:
-- tab debt rows store the sale id, but payment/claim rows store the payment claim id,
-- M-Pesa STK intent id, or airtime order id, and payment_reversal rows store the original
-- payment's id. The strict FK to sales(id) rejects those non-sale values (e.g. recording a
-- tab payment or reversing one), so it must be dropped. Null values remain allowed.
ALTER TABLE credit_transactions DROP FOREIGN KEY fk_credit_txn_sale;
