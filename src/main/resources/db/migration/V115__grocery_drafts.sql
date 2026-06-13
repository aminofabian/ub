-- V115: Grocery counter draft carts — persist in-progress orders before Generate Invoice.

CREATE TABLE branch_grocery_sequences (
    branch_id      CHAR(36)  NOT NULL PRIMARY KEY,
    next_counter   BIGINT    NOT NULL DEFAULT 1,

    CONSTRAINT fk_bgs_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE TABLE grocery_drafts (
    id                    CHAR(36)       NOT NULL PRIMARY KEY,
    business_id           CHAR(36)       NOT NULL,
    branch_id             CHAR(36)       NOT NULL,
    counter_number        BIGINT         NOT NULL,
    status                VARCHAR(16)    NOT NULL DEFAULT 'building',
    created_by            CHAR(36)       NOT NULL,
    invoice_id            CHAR(36)       NULL,
    issue_idempotency_key VARCHAR(64)    NULL,
    client_draft_id       VARCHAR(64)    NULL,
    notes                 VARCHAR(1000)  NULL,
    currency              VARCHAR(3)     NOT NULL DEFAULT 'KES',
    sub_total             DECIMAL(14,2)  NOT NULL DEFAULT 0,
    discount_total        DECIMAL(14,2)  NOT NULL DEFAULT 0,
    tax_total             DECIMAL(14,2)  NOT NULL DEFAULT 0,
    grand_total           DECIMAL(14,2)  NOT NULL DEFAULT 0,
    cancelled_by          CHAR(36)       NULL,
    cancelled_at          TIMESTAMP      NULL,
    cancelled_reason      VARCHAR(500)   NULL,
    issued_at             TIMESTAMP      NULL,
    version               BIGINT         NOT NULL DEFAULT 0,
    created_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_grocery_drafts_branch_counter (branch_id, counter_number),
    UNIQUE KEY uq_grocery_drafts_client_draft (business_id, client_draft_id),
    KEY idx_gd_branch_status (business_id, branch_id, status),
    KEY idx_gd_created_by (business_id, created_by, status),
    KEY idx_gd_updated_at (business_id, updated_at),

    CONSTRAINT fk_gd_business   FOREIGN KEY (business_id)  REFERENCES businesses(id),
    CONSTRAINT fk_gd_branch     FOREIGN KEY (branch_id)    REFERENCES branches(id),
    CONSTRAINT fk_gd_created_by FOREIGN KEY (created_by)   REFERENCES users(id),
    CONSTRAINT fk_gd_invoice    FOREIGN KEY (invoice_id)   REFERENCES grocery_invoices(id)
);

CREATE TABLE grocery_draft_lines (
    id              CHAR(36)       NOT NULL PRIMARY KEY,
    draft_id        CHAR(36)       NOT NULL,
    business_id     CHAR(36)       NOT NULL,
    line_index      INT            NOT NULL,
    item_id         CHAR(36)       NOT NULL,
    item_name       VARCHAR(500)   NOT NULL,
    item_barcode    VARCHAR(128)   NULL,
    quantity        DECIMAL(14,4)  NOT NULL,
    unit_name       VARCHAR(16)    NOT NULL DEFAULT 'each',
    unit_price      DECIMAL(14,4)  NOT NULL,
    discount_amount DECIMAL(14,4)  NOT NULL DEFAULT 0,
    line_total      DECIMAL(14,2)  NOT NULL,
    is_deleted      TINYINT(1)     NOT NULL DEFAULT 0,
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_gdl_draft_line (draft_id, line_index),
    KEY idx_gdl_draft (draft_id),
    KEY idx_gdl_item (business_id, item_id),

    CONSTRAINT fk_gdl_draft FOREIGN KEY (draft_id) REFERENCES grocery_drafts(id) ON DELETE CASCADE,
    CONSTRAINT fk_gdl_item  FOREIGN KEY (item_id)  REFERENCES items(id)
);

CREATE TABLE grocery_draft_audit_log (
    id          CHAR(36)      NOT NULL PRIMARY KEY,
    draft_id    CHAR(36)      NOT NULL,
    user_id     CHAR(36)      NOT NULL,
    action      VARCHAR(32)   NOT NULL,
    line_id     CHAR(36)      NULL,
    old_value   VARCHAR(2000) NULL,
    new_value   VARCHAR(2000) NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_gdal_draft (draft_id, created_at),
    KEY idx_gdal_user (user_id, created_at),

    CONSTRAINT fk_gdal_draft FOREIGN KEY (draft_id) REFERENCES grocery_drafts(id) ON DELETE CASCADE,
    CONSTRAINT fk_gdal_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000118', 'grocery.drafts.cancel.own',
   'Cancel own building grocery counter drafts.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'grocery_clerk'
  AND p.permission_key = 'grocery.drafts.cancel.own';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name IN ('owner', 'admin', 'manager')
  AND p.permission_key = 'grocery.drafts.cancel.own';
