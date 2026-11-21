-- Clinic (ADR 0034): permissions and roles.
--
-- Five permissions. The ADR names four hats; clinic.manage was added for the settings page,
-- because a clinic with no pharmacy has no pharmacy.manage to reuse.
-- Three platform roles: Clinician, Nurse, Receptionist. Owner and Admin get every clinic key;
-- Manager gets all but prescribing. The Pharmacist role is not changed: dispensing a visit's
-- prescription needs only pharmacy.dispense, which it has.
--
-- Clinician and Nurse also get catalog.items.read, so they can find a medicine to prescribe or
-- give; they get no selling, stock or price permission.
--
-- Additive and idempotent. One statement per role, comparing keys to literals (see V415).

INSERT INTO permissions (id, permission_key, description)
SELECT '9d2f6b30-5c8e-4a2b-8b62-7e1a7b3f4101', 'clinic.reception', 'Open a clinic visit and find or add a patient. Sees name, age and phone only.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'clinic.reception');

INSERT INTO permissions (id, permission_key, description)
SELECT '9d2f6b30-5c8e-4a2b-8b62-7e1a7b3f4102', 'clinic.consult', 'Work a clinic visit: add the services given and mark them done.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'clinic.consult');

INSERT INTO permissions (id, permission_key, description)
SELECT '9d2f6b30-5c8e-4a2b-8b62-7e1a7b3f4103', 'clinic.prescribe', 'Write a prescription on a clinic visit.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'clinic.prescribe');

INSERT INTO permissions (id, permission_key, description)
SELECT '9d2f6b30-5c8e-4a2b-8b62-7e1a7b3f4104', 'clinic.perform', 'Carry out a test or procedure on a clinic visit and mark it done.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'clinic.perform');

INSERT INTO permissions (id, permission_key, description)
SELECT '9d2f6b30-5c8e-4a2b-8b62-7e1a7b3f4105', 'clinic.manage', 'Set up the clinic: stations, fee first, review visits and which services are tests.'
WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE permission_key = 'clinic.manage');

INSERT IGNORE INTO roles (id, business_id, role_key, name, description, is_system) VALUES
  ('22222222-0000-0000-0000-00000000000e', NULL, 'clinician', 'Clinician',
   'Sees patients: adds the services given, writes prescriptions and marks tests and procedures done. Does not take payment.',
   TRUE),
  ('22222222-0000-0000-0000-00000000000f', NULL, 'nurse', 'Nurse',
   'Sees patients and carries out tests and procedures. Cannot prescribe or take payment.',
   TRUE),
  ('22222222-0000-0000-0000-000000000010', NULL, 'clinic_receptionist', 'Receptionist',
   'Opens visits, finds or adds patients and takes payment. Sees a patient''s name, age and phone only.',
   TRUE);

-- Owner, Admin
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000001', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.reception', 'clinic.consult', 'clinic.prescribe', 'clinic.perform', 'clinic.manage');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000002', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.reception', 'clinic.consult', 'clinic.prescribe', 'clinic.perform', 'clinic.manage');

-- Manager: everything but prescribing
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000003', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.reception', 'clinic.consult', 'clinic.perform', 'clinic.manage');

-- Clinician
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000e', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.consult', 'clinic.prescribe', 'clinic.perform', 'catalog.items.read');

-- Nurse
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000f', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.consult', 'clinic.perform', 'catalog.items.read');

-- Receptionist: a cashier who can also open visits.
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000010', rp.permission_id
  FROM role_permissions rp
 WHERE rp.role_id = '22222222-0000-0000-0000-000000000004';

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-000000000010', p.id
  FROM permissions p
 WHERE p.permission_key IN ('clinic.reception');

