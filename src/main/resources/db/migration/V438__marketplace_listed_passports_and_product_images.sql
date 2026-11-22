-- ADR 0038, P0.1: the public supplier directory lists passports that are marked as listed, instead of
-- scanning every shop's private supplier rows.
--
-- listed           the passport appears in the public directory.
-- hide_prices      the supplier keeps its prices off public pages.
-- hide_contacts    the supplier keeps its phone and email off public pages.
-- image_url        a picture for a passport product.
-- price_confirmed  the price may be shown publicly. A price copied in later from a shop's own cost
--                  stays private until the supplier saves it in the portal.

ALTER TABLE marketplace_suppliers
  ADD COLUMN listed BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN hide_prices BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN hide_contacts BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE marketplace_supplier_products
  ADD COLUMN image_url VARCHAR(2048) NULL;

ALTER TABLE marketplace_supplier_price_offers
  ADD COLUMN price_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

-- Suppliers who have claimed their passport (an active portal user exists) start as listed.
UPDATE marketplace_suppliers
SET listed = TRUE
WHERE status = 'active'
  AND id IN (SELECT marketplace_supplier_id FROM supplier_users WHERE active = TRUE);

-- Their existing prices stay public: the supplier already sees and edits them in the portal, and can
-- hide them. Zero is a placeholder, never a price.
UPDATE marketplace_supplier_price_offers
SET price_confirmed = TRUE
WHERE unit_price > 0
  AND marketplace_supplier_id IN (SELECT id FROM marketplace_suppliers WHERE listed = TRUE);

CREATE INDEX idx_marketplace_suppliers_listed ON marketplace_suppliers (listed, status);
