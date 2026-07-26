-- Marketplace supplier payout / payment profile for Supplier Portal Payment Details.

ALTER TABLE marketplace_suppliers
  ADD COLUMN business_legal_name VARCHAR(255) NULL AFTER contact_phone,
  ADD COLUMN paybill VARCHAR(64) NULL AFTER business_legal_name,
  ADD COLUMN till_number VARCHAR(64) NULL AFTER paybill,
  ADD COLUMN bank_name VARCHAR(128) NULL AFTER till_number,
  ADD COLUMN bank_branch VARCHAR(128) NULL AFTER bank_name,
  ADD COLUMN bank_account_number VARCHAR(64) NULL AFTER bank_branch,
  ADD COLUMN bank_account_name VARCHAR(255) NULL AFTER bank_account_number,
  ADD COLUMN mobile_money VARCHAR(64) NULL AFTER bank_account_name,
  ADD COLUMN preferred_payment_method VARCHAR(64) NULL AFTER mobile_money,
  ADD COLUMN tax_pin VARCHAR(64) NULL AFTER preferred_payment_method,
  ADD COLUMN vat_number VARCHAR(64) NULL AFTER tax_pin,
  ADD COLUMN contact_person VARCHAR(255) NULL AFTER vat_number;
