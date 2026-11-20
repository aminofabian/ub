-- Pharmacy — a credit account for each medical scheme (ADR 0033, Phase 3).
--
--  * pharmacy_schemes.credit_customer_id: the customer record that stands for the scheme. At the
--    till the scheme's share of a sale is put on this customer's tab with the existing "customer
--    credit" payment, so what a scheme owes is tracked by the existing credit ledger. No new
--    payment method and no new ledger account.
--
-- Additive only, no backfill: schemes created before this get their account the next time they
-- are saved. Idempotent. A nullable column, instant on MySQL 8.4 / MariaDB 10.11.

SET @s := IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pharmacy_schemes' AND COLUMN_NAME = 'credit_customer_id') = 0,
  'ALTER TABLE pharmacy_schemes ADD COLUMN credit_customer_id CHAR(36) NULL',
  'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
