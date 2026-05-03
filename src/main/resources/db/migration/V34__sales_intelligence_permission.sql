-- Category × sales attribution reporting (CATEGORY_SYSTEM_DESIGN.md roadmap step 6).

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000073', 'sales.intelligence.read',
   'View POS sales revenue breakdowns by catalog category.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000073'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000073'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000073');
