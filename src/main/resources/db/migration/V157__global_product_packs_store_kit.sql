-- Link starter packs to onboarding/store-type kits (store_kit_id was unused).

UPDATE global_product_packs
   SET store_kit_id = 'mini-mart'
 WHERE code = 'mini-mart-starter'
   AND (store_kit_id IS NULL OR store_kit_id = '');

UPDATE global_product_packs
   SET store_kit_id = 'mini-mart'
 WHERE code = 'beverages-pack'
   AND (store_kit_id IS NULL OR store_kit_id = '');

UPDATE global_product_packs
   SET store_kit_id = 'full-grocery'
 WHERE code = 'grocery-basics'
   AND (store_kit_id IS NULL OR store_kit_id = '');
