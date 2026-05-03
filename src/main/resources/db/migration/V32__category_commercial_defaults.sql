-- Category commercial defaults, supplier primary flag, category ↔ price_rule junction (design: CATEGORY_SYSTEM_DESIGN.md).

ALTER TABLE categories
  ADD COLUMN description TEXT NULL AFTER name,
  ADD COLUMN default_markup_pct DECIMAL(9, 4) NULL AFTER icon,
  ADD COLUMN default_tax_rate_id CHAR(36) NULL AFTER default_markup_pct,
  ADD CONSTRAINT fk_categories_default_tax FOREIGN KEY (default_tax_rate_id) REFERENCES tax_rates (id) ON DELETE SET NULL;

ALTER TABLE category_supplier_links
  ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT FALSE AFTER sort_order;

CREATE TABLE category_price_rules (
  category_id CHAR(36) NOT NULL,
  price_rule_id CHAR(36) NOT NULL,
  precedence INT NOT NULL DEFAULT 0,
  PRIMARY KEY (category_id, price_rule_id),
  CONSTRAINT fk_cpr_category FOREIGN KEY (category_id) REFERENCES categories (id) ON DELETE CASCADE,
  CONSTRAINT fk_cpr_rule FOREIGN KEY (price_rule_id) REFERENCES price_rules (id) ON DELETE CASCADE
);

CREATE INDEX idx_cpr_rule ON category_price_rules (price_rule_id);
