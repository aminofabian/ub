-- =============================================================================
-- V137 — Pilot seed for supplier marketplace directory (dev / demo).
-- Idempotent via INSERT IGNORE on fixed UUIDs.
-- =============================================================================

INSERT IGNORE INTO marketplace_suppliers (
  id, name, description, contact_email, contact_phone, status,
  delivery_regions_json, category_tags_json, trust_score, version, created_at, updated_at
) VALUES
(
  'a1000001-0000-4000-8000-000000000001',
  'Nairobi Fresh Distributors',
  'FMCG wholesaler serving Nairobi and Kiambu. Rice, cooking oil, sugar, and household staples.',
  'orders@nairobifresh.example',
  '254712000001',
  'active',
  '["Nairobi","Kiambu","Machakos"]',
  '["FMCG","Groceries","Staples"]',
  0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
),
(
  'a1000001-0000-4000-8000-000000000002',
  'Rift Valley Dairy Co',
  'Fresh milk, yoghurt, and UHT dairy for retail and HORECA.',
  'sales@rvdairy.example',
  '254722000002',
  'active',
  '["Nairobi","Nakuru","Eldoret"]',
  '["Dairy","Cold chain"]',
  0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
),
(
  'a1000001-0000-4000-8000-000000000003',
  'Coastal Care Supplies',
  'Personal care, detergents, and cleaning products for shops and hotels.',
  'hello@coastalcare.example',
  '254733000003',
  'active',
  '["Mombasa","Kilifi","Nairobi"]',
  '["Personal care","Cleaning"]',
  0, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
);

INSERT IGNORE INTO marketplace_supplier_products (
  id, marketplace_supplier_id, name, barcode, sku, category_name, description,
  pack_size, pack_unit, min_order_qty, status, version, created_at, updated_at
) VALUES
('b2000001-0000-4000-8000-000000000011', 'a1000001-0000-4000-8000-000000000001', 'Pishori Rice 5kg', '6161101234501', 'NF-RICE-5', 'Staples', 'Premium aromatic rice', 5, 'kg', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000012', 'a1000001-0000-4000-8000-000000000001', 'Cooking Oil 3L', '6161101234502', 'NF-OIL-3', 'Staples', 'Vegetable cooking oil', 3, 'L', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000013', 'a1000001-0000-4000-8000-000000000001', 'White Sugar 2kg', '6161101234503', 'NF-SUG-2', 'Staples', 'Refined white sugar', 2, 'kg', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000014', 'a1000001-0000-4000-8000-000000000001', 'Maize Flour 2kg', '6161101234504', 'NF-FLOUR-2', 'Staples', 'Sifted maize meal', 2, 'kg', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000021', 'a1000001-0000-4000-8000-000000000002', 'Fresh Milk 500ml', '6162201234501', 'RD-MILK-500', 'Dairy', 'Pasteurised fresh milk', 0.5, 'L', 12, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000022', 'a1000001-0000-4000-8000-000000000002', 'UHT Milk 1L', '6162201234502', 'RD-UHT-1', 'Dairy', 'Long-life whole milk', 1, 'L', 6, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000023', 'a1000001-0000-4000-8000-000000000002', 'Natural Yoghurt 500g', '6162201234503', 'RD-YOG-500', 'Dairy', 'Plain set yoghurt', 0.5, 'kg', 6, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000024', 'a1000001-0000-4000-8000-000000000002', 'Drinking Yoghurt 250ml', '6162201234504', 'RD-DYOG-250', 'Dairy', 'Mango drinking yoghurt', 0.25, 'L', 12, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000031', 'a1000001-0000-4000-8000-000000000003', 'Bar Soap 1kg', '6163301234501', 'CC-SOAP-1', 'Personal care', 'Multipurpose laundry bar', 1, 'kg', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000032', 'a1000001-0000-4000-8000-000000000003', 'Dishwashing Liquid 750ml', '6163301234502', 'CC-DISH-750', 'Cleaning', 'Lemon dishwashing liquid', 0.75, 'L', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000033', 'a1000001-0000-4000-8000-000000000003', 'Toilet Tissue 10-pack', '6163301234503', 'CC-TISSUE-10', 'Household', 'Soft 2-ply tissue', 10, 'pcs', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('b2000001-0000-4000-8000-000000000034', 'a1000001-0000-4000-8000-000000000003', 'Hand Wash 500ml', '6163301234504', 'CC-HAND-500', 'Personal care', 'Antibacterial hand wash', 0.5, 'L', 1, 'active', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT IGNORE INTO marketplace_supplier_price_offers (
  id, marketplace_supplier_id, product_id, package_size, package_unit, region_code,
  min_qty, unit_price, currency, available, effective_from, version, created_at, updated_at
) VALUES
('c3000001-0000-4000-8000-000000000011', 'a1000001-0000-4000-8000-000000000001', 'b2000001-0000-4000-8000-000000000011', 5, 'kg', 'NBO', 1, 890.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000012', 'a1000001-0000-4000-8000-000000000001', 'b2000001-0000-4000-8000-000000000012', 3, 'L', 'NBO', 1, 720.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000013', 'a1000001-0000-4000-8000-000000000001', 'b2000001-0000-4000-8000-000000000013', 2, 'kg', 'NBO', 1, 310.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000014', 'a1000001-0000-4000-8000-000000000001', 'b2000001-0000-4000-8000-000000000014', 2, 'kg', 'NBO', 1, 185.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000021', 'a1000001-0000-4000-8000-000000000002', 'b2000001-0000-4000-8000-000000000021', 0.5, 'L', 'NBO', 12, 55.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000022', 'a1000001-0000-4000-8000-000000000002', 'b2000001-0000-4000-8000-000000000022', 1, 'L', 'NBO', 6, 145.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000023', 'a1000001-0000-4000-8000-000000000002', 'b2000001-0000-4000-8000-000000000023', 0.5, 'kg', 'NBO', 6, 120.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000024', 'a1000001-0000-4000-8000-000000000002', 'b2000001-0000-4000-8000-000000000024', 0.25, 'L', 'NBO', 12, 70.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000031', 'a1000001-0000-4000-8000-000000000003', 'b2000001-0000-4000-8000-000000000031', 1, 'kg', 'NBO', 1, 210.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000032', 'a1000001-0000-4000-8000-000000000003', 'b2000001-0000-4000-8000-000000000032', 0.75, 'L', 'NBO', 1, 165.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000033', 'a1000001-0000-4000-8000-000000000003', 'b2000001-0000-4000-8000-000000000033', 10, 'pcs', 'NBO', 1, 380.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('c3000001-0000-4000-8000-000000000034', 'a1000001-0000-4000-8000-000000000003', 'b2000001-0000-4000-8000-000000000034', 0.5, 'L', 'NBO', 1, 195.00, 'KES', TRUE, CURRENT_TIMESTAMP(6), 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));

INSERT IGNORE INTO supplier_identity_index (
  id, source, business_id, supplier_id, marketplace_supplier_id,
  name_normalized, phone_normalized, email_normalized, tax_id_normalized, region_hint, created_at, updated_at
) VALUES
('d4000001-0000-4000-8000-000000000001', 'marketplace', NULL, NULL, 'a1000001-0000-4000-8000-000000000001', 'nairobi fresh distributors', '254712000001', 'orders@nairobifresh.example', NULL, 'Nairobi', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('d4000001-0000-4000-8000-000000000002', 'marketplace', NULL, NULL, 'a1000001-0000-4000-8000-000000000002', 'rift valley dairy co', '254722000002', 'sales@rvdairy.example', NULL, 'Nairobi', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)),
('d4000001-0000-4000-8000-000000000003', 'marketplace', NULL, NULL, 'a1000001-0000-4000-8000-000000000003', 'coastal care supplies', '254733000003', 'hello@coastalcare.example', NULL, 'Mombasa', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6));
