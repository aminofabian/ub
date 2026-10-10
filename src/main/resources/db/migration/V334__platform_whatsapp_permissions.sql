-- Platform WhatsApp channel permissions (super-admin only; not granted to tenant roles).
-- Mirrors V121__global_catalog_permissions.sql. Consumed by the super-admin console and
-- available for frontend gating of the WhatsApp numbers screen.
-- See docs/scopes/whatsapp-crm/SCOPE.md §6.8 and §8.

INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000410', 'platform.whatsapp.read',
   'View WhatsApp numbers and their shop routing.'),
  ('11111111-0000-0000-0000-000000000411', 'platform.whatsapp.manage',
   'Assign Meta WhatsApp numbers to shops and pause/resume them.');
