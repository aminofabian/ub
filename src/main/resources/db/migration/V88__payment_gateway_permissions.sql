INSERT INTO permissions (id, permission_key, description) VALUES
  ('d1e7f9a2-3b4c-4d5e-8f6a-7b8c9d0e1f2a', 'payments.gateways.read',
   'View available payment gateways and own gateway configurations.'),
  ('e2f8a0b3-4c5d-4e6f-9a0b-8c9d0e1f2b3c', 'payments.gateways.write',
   'Create, update, delete, test, activate, and deactivate payment gateway configurations.'),
  ('f3a9b1c4-5d6e-4f7a-0b1c-9d0e1f2a3b4d', 'payments.platform.manage',
   'Manage platform-level payment gateway availability. Super admin only.');
