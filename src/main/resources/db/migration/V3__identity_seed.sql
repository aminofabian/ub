-- Slice 2 — Identity primitives seed (PHASE_1_PLAN.md §2.2)
-- Seeds:
--   * The Phase-1 permission keys actually checked by controllers/services.
--   * System roles (owner, admin, manager, cashier, viewer) with their
--     Phase-1 permission grants. System roles use business_id = NULL.
--
-- IDs are stable literal UUIDs so test fixtures can reference them directly.
-- Tenant-bootstrap (Phase 2 candidate) may later clone these into per-tenant
-- copies; for now Phase 1 assigns users straight to the system rows.

-- ---------- Permissions ----------------------------------------------------
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000010', 'business.manage_settings',     'Edit tenant settings (name, timezone, currency).'),
  ('11111111-0000-0000-0000-000000000011', 'business.manage_subscription', 'Change subscription tier and renewal dates.'),

  ('11111111-0000-0000-0000-000000000020', 'users.list',                   'List users in the tenant.'),
  ('11111111-0000-0000-0000-000000000021', 'users.create',                 'Create users in the tenant.'),
  ('11111111-0000-0000-0000-000000000022', 'users.update',                 'Edit users in the tenant.'),
  ('11111111-0000-0000-0000-000000000023', 'users.deactivate',             'Deactivate users in the tenant.'),
  ('11111111-0000-0000-0000-000000000024', 'users.assign_role',            'Reassign a user to a different role.'),

  ('11111111-0000-0000-0000-000000000030', 'roles.list',                   'List tenant and system roles.'),
  ('11111111-0000-0000-0000-000000000031', 'roles.create',                 'Create tenant-scoped roles.'),
  ('11111111-0000-0000-0000-000000000032', 'roles.update',                 'Edit tenant-scoped roles.'),

  ('11111111-0000-0000-0000-000000000040', 'catalog.items.read',           'Read items, variants, search.'),
  ('11111111-0000-0000-0000-000000000041', 'catalog.items.write',          'Create or edit items and variants.'),
  ('11111111-0000-0000-0000-000000000042', 'catalog.categories.write',     'Create or edit categories, aisles, item types.');

-- ---------- System roles ---------------------------------------------------
INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000001', NULL, 'owner',   'Owner',   'Full access to a single tenant.',                          TRUE),
  ('22222222-0000-0000-0000-000000000002', NULL, 'admin',   'Admin',   'Full access except subscription management.',              TRUE),
  ('22222222-0000-0000-0000-000000000003', NULL, 'manager', 'Manager', 'Operational access to users (read) and full catalog.',     TRUE),
  ('22222222-0000-0000-0000-000000000004', NULL, 'cashier', 'Cashier', 'Read-only catalog access; auth flows are always allowed.', TRUE),
  ('22222222-0000-0000-0000-000000000005', NULL, 'viewer',  'Viewer',  'Read-only access across the tenant.',                      TRUE);

-- ---------- Role → permission grants ---------------------------------------
-- owner: every Phase-1 permission.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000001', id FROM permissions;

-- admin: everything except business.manage_subscription.
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000002', id
  FROM permissions
 WHERE permission_key <> 'business.manage_subscription';

-- manager: users.list + catalog.* (read + write + categories.write).
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000003', id
  FROM permissions
 WHERE permission_key IN (
   'users.list',
   'catalog.items.read',
   'catalog.items.write',
   'catalog.categories.write'
 );

-- cashier: catalog.items.read only (auth.* endpoints are ambient).
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000004', id
  FROM permissions
 WHERE permission_key = 'catalog.items.read';

-- viewer: every *.read / *.list (no writes, no deactivate, no role changes).
INSERT INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000005', id
  FROM permissions
 WHERE permission_key IN (
   'users.list',
   'roles.list',
   'catalog.items.read'
 );
