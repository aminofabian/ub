-- Tax breakdown on completed sales, so a sale is the raw material for a KRA
-- eTIMS invoice. Prices are treated as tax-inclusive (the Kenyan retail norm):
-- tax is extracted from the gross line total, so net + tax = gross and no
-- customer-facing amount changes.

ALTER TABLE tax_rates
  ADD COLUMN etims_tax_code VARCHAR(8) NULL AFTER inclusive;

ALTER TABLE sales
  ADD COLUMN tax_total DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER grand_total;

ALTER TABLE sale_items
  ADD COLUMN tax_rate_id CHAR(36) NULL AFTER discount_name,
  ADD COLUMN tax_rate_percent DECIMAL(7,3) NULL AFTER tax_rate_id,
  ADD COLUMN tax_code VARCHAR(8) NULL AFTER tax_rate_percent,
  ADD COLUMN tax_amount DECIMAL(14,2) NOT NULL DEFAULT 0 AFTER tax_code;
