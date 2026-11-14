-- The usual helpers on a service and what each is paid, set once by the owner (ADR 0016,
-- amendment 4): "After shave: 50 shillings", "Groomer: 20% of the price". At the till the cashier
-- taps a role and names the person; the pay comes from this row, not from what the cashier types.
--
-- A new table only; nothing existing is altered. The helper and the pay worked out from the role
-- are stored on the line in service_helpers exactly as before (V378, V381), so a role can later be
-- changed or removed without touching any visit or sale already recorded.

CREATE TABLE IF NOT EXISTS service_helper_roles (
  id           CHAR(36)      NOT NULL PRIMARY KEY,
  business_id  CHAR(36)      NOT NULL,
  name         VARCHAR(60)   NOT NULL,
  -- PERCENT: pay_value percent of the service's price. AMOUNT: pay_value whole shillings.
  pay_kind     VARCHAR(10)   NOT NULL,
  pay_value    DECIMAL(14,2) NOT NULL,
  sort_order   INT           NOT NULL DEFAULT 0,
  version      BIGINT        NOT NULL DEFAULT 0,
  created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_service_helper_roles_name (business_id, name),
  CONSTRAINT fk_service_helper_roles_business FOREIGN KEY (business_id) REFERENCES businesses (id)
);
