-- V125: Butcher cashier — dedicated counter POS at /butcher (not /cashier).
--
-- A butcher_cashier:
--   * Uses the butcher POS workspace (category pills, kg vs piece tiles, order panel).
--   * Has the same sale/shift/supply permissions as a standard cashier.
--   * Is branch-locked like cashier.
--   * Must not be redirected to the generic /cashier quick-sale UI.

INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000009', NULL, 'butcher_cashier', 'Butcher Cashier',
   'Point of sale at the butcher counter. Uses the butcher workspace; same sale permissions as cashier.',
   TRUE);

-- Mirror cashier permissions (all grants on role 004).
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000009', rp.permission_id
  FROM role_permissions rp
 WHERE rp.role_id = '22222222-0000-0000-0000-000000000004';
