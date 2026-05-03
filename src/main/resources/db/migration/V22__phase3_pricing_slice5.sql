-- Phase 3 Slice 5 — historical selling/buying prices, margin rules, tax rates (PHASE_3_PLAN.md).

CREATE TABLE selling_prices (
  id              CHAR(36)       PRIMARY KEY,
  business_id     CHAR(36)       NOT NULL,
  item_id         CHAR(36)       NOT NULL,
  branch_id       CHAR(36)       NULL,
  price           DECIMAL(14, 2) NOT NULL,
  effective_from  DATE           NOT NULL,
  effective_to    DATE           NULL,
  set_by          CHAR(36)       NULL,
  notes           VARCHAR(2000)  NULL,
  created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_selling_prices_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE,
  CONSTRAINT fk_selling_prices_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
  CONSTRAINT fk_selling_prices_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

CREATE INDEX idx_selling_prices_lookup ON selling_prices (business_id, item_id, branch_id, effective_from);

CREATE TABLE buying_prices (
  id              CHAR(36)       PRIMARY KEY,
  business_id     CHAR(36)       NOT NULL,
  item_id         CHAR(36)       NOT NULL,
  supplier_id     CHAR(36)       NOT NULL,
  unit_cost       DECIMAL(14, 4) NOT NULL,
  effective_from  DATE         NOT NULL,
  effective_to    DATE           NULL,
  source_type     VARCHAR(32)    NOT NULL DEFAULT 'manual',
  set_by          CHAR(36)       NULL,
  notes           VARCHAR(2000)  NULL,
  created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_buying_prices_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE,
  CONSTRAINT fk_buying_prices_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
  CONSTRAINT fk_buying_prices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
);

CREATE INDEX idx_buying_prices_lookup ON buying_prices (business_id, item_id, supplier_id, effective_from);

CREATE TABLE price_rules (
  id           CHAR(36)      PRIMARY KEY,
  business_id  CHAR(36)      NOT NULL,
  name         VARCHAR(191)  NOT NULL,
  rule_type    VARCHAR(32)   NOT NULL,
  params_json  TEXT          NOT NULL,
  active       BOOLEAN       NOT NULL DEFAULT TRUE,
  version      BIGINT        NOT NULL DEFAULT 0,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_price_rules_business_name (business_id, name),
  CONSTRAINT fk_price_rules_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE
);

CREATE TABLE tax_rates (
  id            CHAR(36)      PRIMARY KEY,
  business_id   CHAR(36)      NOT NULL,
  name          VARCHAR(191)  NOT NULL,
  rate_percent  DECIMAL(7, 3) NOT NULL,
  inclusive     BOOLEAN       NOT NULL DEFAULT FALSE,
  active        BOOLEAN       NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_tax_rates_business_name (business_id, name),
  CONSTRAINT fk_tax_rates_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000060', 'pricing.read',
   'View prices, rules, tax rates, and margin suggestions.'),
  ('11111111-0000-0000-0000-000000000061', 'pricing.sell_price.set',
   'Insert historical selling prices.'),
  ('11111111-0000-0000-0000-000000000062', 'pricing.cost_price.set',
   'Insert historical buying / cost prices.'),
  ('11111111-0000-0000-0000-000000000063', 'pricing.rules.manage',
   'Create and update price rules and tax rates.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000060'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000061'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000062'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000063'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000060'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000061'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000062'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000063'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000060'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000061'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000062');
