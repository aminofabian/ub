-- Pharmacy — the Pharmacist role can look after medicine stock (ADR 0033).
--
-- V415 built the Pharmacist role from the Cashier's permissions plus the pharmacy keys. A cashier
-- has no stock permissions, so a pharmacist could not open Stock safety, quarantine a batch, run
-- a recall or see the ordering list: the very things the role exists for. This adds
-- inventory.read and inventory.write to the Pharmacist system role. Nothing else changes.
--
-- Idempotent (INSERT IGNORE). No DDL.

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT '22222222-0000-0000-0000-00000000000d', p.id
  FROM permissions p
 WHERE p.permission_key IN ('inventory.read', 'inventory.write');
