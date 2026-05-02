-- Allow managers to list roles and reassign users (operational staffing).
INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000030'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000024');
