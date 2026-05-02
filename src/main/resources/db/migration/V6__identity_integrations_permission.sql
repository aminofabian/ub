-- integrations.api_keys.manage — owner + admin (PHASE_1_PLAN.md §3.3)

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000043', 'integrations.api_keys.manage',
   'Create, list, and revoke tenant API keys.');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000043'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000043');
