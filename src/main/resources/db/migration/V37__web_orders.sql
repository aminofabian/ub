-- Phase 16 — Web checkout draft orders (payment integration follows).

CREATE TABLE web_orders (
    id                   CHAR(36)      NOT NULL PRIMARY KEY,
    business_id          CHAR(36)      NOT NULL,
    cart_id              CHAR(36)      NOT NULL,
    catalog_branch_id    CHAR(36)      NOT NULL,
    status               VARCHAR(24)   NOT NULL,
    currency             VARCHAR(8)    NOT NULL,
    grand_total          DECIMAL(14,2) NOT NULL,
    customer_name        VARCHAR(255)  NOT NULL,
    customer_phone       VARCHAR(64)   NOT NULL,
    customer_email       VARCHAR(255)  NULL,
    notes                VARCHAR(2000) NULL,
    created_at           TIMESTAMP(6)  NOT NULL,
    updated_at           TIMESTAMP(6)  NOT NULL,
    CONSTRAINT fk_web_orders_business        FOREIGN KEY (business_id)       REFERENCES businesses(id),
    CONSTRAINT fk_web_orders_catalog_branch  FOREIGN KEY (catalog_branch_id) REFERENCES branches(id),
    KEY idx_web_orders_business_created (business_id, created_at),
    KEY idx_web_orders_status (business_id, status)
);

CREATE TABLE web_order_lines (
    id           CHAR(36)        NOT NULL PRIMARY KEY,
    order_id     CHAR(36)        NOT NULL,
    item_id      CHAR(36)        NOT NULL,
    item_name    VARCHAR(500)    NOT NULL,
    variant_name VARCHAR(500)    NULL,
    quantity     DECIMAL(14,4)   NOT NULL,
    unit_price   DECIMAL(14,4)   NOT NULL,
    line_total   DECIMAL(14,2)   NOT NULL,
    line_index   INT             NOT NULL,
    CONSTRAINT fk_web_order_lines_order FOREIGN KEY (order_id) REFERENCES web_orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_web_order_lines_item  FOREIGN KEY (item_id)  REFERENCES items(id),
    KEY idx_web_order_lines_order (order_id)
);
