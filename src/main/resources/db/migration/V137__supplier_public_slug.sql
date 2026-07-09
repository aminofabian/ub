-- Public marketplace supplier slugs (cross-tenant unique when set).
ALTER TABLE suppliers
    ADD COLUMN public_slug VARCHAR(96) NULL;

CREATE UNIQUE INDEX uk_suppliers_public_slug
    ON suppliers (public_slug);
