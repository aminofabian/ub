-- Phase 3 Slice 1 — inventory ledger completions (PHASE_3_PLAN.md §Slice 1).

-- Batches without a supplier (opening balance, stock-count gain).
ALTER TABLE inventory_batches DROP FOREIGN KEY fk_ib_supplier;
ALTER TABLE inventory_batches MODIFY supplier_id CHAR(36) NULL;
ALTER TABLE inventory_batches
  ADD CONSTRAINT fk_ib_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000054', 'inventory.read',
   'View stock movements, batches, and inventory-related reads.'),
  ('11111111-0000-0000-0000-000000000055', 'inventory.write',
   'Record opening balances, adjustments, and wastage (non-POS).');

INSERT INTO role_permissions (role_id, permission_id) VALUES
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000054'),
  ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000055'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000054'),
  ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000055'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000054'),
  ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000055');
