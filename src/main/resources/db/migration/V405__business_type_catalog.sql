-- ADR 0029: the business-type catalogue is platform data; the type codes stay a fixed enum.
-- Platform-global (not tenant-scoped): the same catalogue for every shop. Carries only presentation
-- (label, hint, image, section, order, enabled) — behaviour still branches on the code, defined in
-- StoreTypeCodes. The code is the primary key. Seed rows are the built-in defaults, mirrored in
-- BusinessTypeCatalogDefaults; a startup runner backfills any missing code on existing databases
-- without overwriting an edit, so this INSERT only needs to seed a fresh DB.
CREATE TABLE business_type_catalog (
    code            VARCHAR(64)   NOT NULL,
    label           VARCHAR(120)  NOT NULL,
    hint            VARCHAR(400)  NULL,
    section         VARCHAR(32)   NOT NULL,
    display_order   INT           NOT NULL,
    enabled         TINYINT(1)    NOT NULL DEFAULT 1,
    image_url       VARCHAR(1024) NULL,
    image_public_id VARCHAR(512)  NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (code),
    KEY idx_business_type_catalog_order (display_order, code)
);

INSERT INTO business_type_catalog
    (code, label, hint, section, display_order, enabled, updated_at)
VALUES
    ('butchery',      'Butchery',             'Meat, poultry, fish, eggs, and value-added products',    'shop',    10,  1, NOW(6)),
    ('mini-mart',     'Mini mart',            'Snacks, drinks, and everyday staples',                   'shop',    20,  1, NOW(6)),
    ('full-grocery',  'Full grocery',         'Wide range across all departments',                      'shop',    30,  1, NOW(6)),
    ('fresh-market',  'Fresh market',         'Produce, dairy, and protein focus',                      'shop',    40,  1, NOW(6)),
    ('mixed-shop',    'Mixed shop',           'Groceries plus general retail',                          'shop',    50,  1, NOW(6)),
    ('cosmetics',     'Cosmetics',            'Beauty, skin care, hair, and grooming',                  'shop',    60,  1, NOW(6)),
    ('wines-spirits', 'Wines & spirits',      'Beer, wine, spirits, and mixers',                        'shop',    70,  1, NOW(6)),
    ('pharmacy',      'Pharmacy / chemist',   'Medicines, OTC, first aid, and health supplies',         'shop',    80,  1, NOW(6)),
    ('barbershop',    'Kinyozi / barbershop', 'Haircuts and shaves, with what each barber earns',       'service', 90,  1, NOW(6)),
    ('salon',         'Salon',                'Hair, braids, colour, and treatments',                   'service', 100, 1, NOW(6)),
    ('spa',           'Spa',                  'Massage, facials, and wellness',                         'service', 110, 1, NOW(6)),
    ('nail-studio',   'Nail studio',          'Manicure, pedicure, and nail art',                       'service', 120, 1, NOW(6)),
    ('beauty-parlour','Beauty parlour',       'Make-up, lashes, brows, and grooming',                   'service', 130, 1, NOW(6)),
    ('restaurant',    'Restaurant',           'Meals made to order, with tickets for the kitchen',      'food',    140, 1, NOW(6)),
    ('cafe',          'Café',                 'Coffee, tea, snacks, and light meals',                   'food',    150, 1, NOW(6)),
    ('fast-food',     'Fast food / takeaway', 'Chips, chicken, burgers — pay, then collect by number',  'food',    160, 1, NOW(6)),
    ('bar',           'Bar',                  'Drinks and bites, made at the bar or in the kitchen',    'food',    170, 1, NOW(6)),
    ('lodging',       'Guest house / rooms',  'A few rooms to let — on their own, or above a bar',      'lodging', 180, 1, NOW(6)),
    ('other',         'Other',                'A different kind of shop — add your own product sections','shop',    190, 1, NOW(6));
