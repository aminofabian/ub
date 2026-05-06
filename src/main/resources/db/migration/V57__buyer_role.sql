-- Shopper accounts: default self-signup role (Phase 17). No dashboard permissions —
-- buyers use the public storefront plus authenticated /api/v1/me for profile.

INSERT INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-000000000006', NULL, 'buyer', 'Buyer',
   'Customer account for the online shop. No back-office permissions.', TRUE);
