-- Supplier counter orders: the buyer's trade account the clerk priced this invoice for. Null for every other invoice.
ALTER TABLE grocery_invoices
    ADD trade_account_id VARCHAR(36) NULL;
