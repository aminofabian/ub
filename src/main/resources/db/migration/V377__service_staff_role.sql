-- A role for the people who do the work in a service business (barber, stylist, therapist).
--
-- It lets them sign in and see their own earnings, and nothing else: no till, no stock, no
-- other person's pay. The owner can still give someone a wider role (cashier, manager) instead.
-- Not branch-locked: a staff member may work at any branch (ADR 0014).
--
-- INSERT IGNORE so a re-run after a partial failure does not die on rows already written.

INSERT IGNORE INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-00000000000a', NULL, 'service_staff', 'Service staff',
   'Signs in to see their own commission, tips and what they are owed. No till access.',
   TRUE);

-- commissions.read_own: 6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43 (V369).
-- Managers and cashiers may serve customers too, so they can see their own earnings as well.
INSERT IGNORE INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-00000000000a', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43'),
  ('22222222-0000-0000-0000-000000000003', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43'),
  ('22222222-0000-0000-0000-000000000004', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43'),
  ('22222222-0000-0000-0000-000000000009', '6c1d8b2f-4a63-4e2b-8d75-8f3b2a1c7e43');
