-- Phase 16 — Guest web carts (catalog branch pricing; TTL for abuse control).

CREATE TABLE web_carts (
    id                  CHAR(36)     NOT NULL PRIMARY KEY,
    business_id         CHAR(36)     NOT NULL,
    catalog_branch_id   CHAR(36)     NOT NULL,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    expires_at          TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_web_carts_business       FOREIGN KEY (business_id)       REFERENCES businesses(id),
    CONSTRAINT fk_web_carts_catalog_branch FOREIGN KEY (catalog_branch_id) REFERENCES branches(id),
    KEY idx_web_carts_business (business_id),
    KEY idx_web_carts_expires (expires_at)
);

CREATE TABLE web_cart_lines (
    id          CHAR(36)        NOT NULL PRIMARY KEY,
    cart_id     CHAR(36)        NOT NULL,
    item_id     CHAR(36)        NOT NULL,
    quantity    DECIMAL(14,4)   NOT NULL,
    created_at  TIMESTAMP(6)   NOT NULL,
    updated_at  TIMESTAMP(6)   NOT NULL,
    CONSTRAINT fk_web_cart_lines_cart FOREIGN KEY (cart_id) REFERENCES web_carts(id) ON DELETE CASCADE,
    CONSTRAINT fk_web_cart_lines_item FOREIGN KEY (item_id) REFERENCES items(id),
    UNIQUE KEY uq_web_cart_lines_cart_item (cart_id, item_id),
    KEY idx_web_cart_lines_cart (cart_id)
);
