-- Phase 8 Slice 3 — CSV data import (tenant tooling).

-- UUID suffix ...107: ...045 is suppliers.write (V14); do not reuse seeded ids.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000107', 'integrations.imports.manage',
   'Run CSV imports for catalog, suppliers, and opening stock (dry-run + commit).');

INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000107'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000107');
