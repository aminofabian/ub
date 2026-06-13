-- V114: Cashier POS draft carts — persist in-progress ring-ups before checkout.

CREATE TABLE branch_pos_sequences (
    branch_id     CHAR(36)  NOT NULL PRIMARY KEY,
    next_ticket   BIGINT    NOT NULL DEFAULT 1,

    CONSTRAINT fk_bps_branch FOREIGN KEY (branch_id) REFERENCES branches(id)
);

CREATE TABLE pos_drafts (
    id               CHAR(36)       NOT NULL PRIMARY KEY,
    business_id      CHAR(36)       NOT NULL,
    branch_id        CHAR(36)       NOT NULL,
    ticket_number    BIGINT         NOT NULL,
    status           VARCHAR(16)    NOT NULL DEFAULT 'pending',
    created_by       CHAR(36)       NOT NULL,
    shift_id         CHAR(36)       NULL,
    sale_id          CHAR(36)       NULL,
    customer_id      CHAR(36)       NULL,
    client_draft_id  VARCHAR(64)    NULL,
    currency         VARCHAR(3)     NOT NULL DEFAULT 'KES',
    sub_total        DECIMAL(14,2)  NOT NULL DEFAULT 0,
    discount_total   DECIMAL(14,2)  NOT NULL DEFAULT 0,
    tax_total        DECIMAL(14,2)  NOT NULL DEFAULT 0,
    grand_total      DECIMAL(14,2)  NOT NULL DEFAULT 0,
    cancelled_by     CHAR(36)       NULL,
    cancelled_at     TIMESTAMP      NULL,
    cancelled_reason VARCHAR(500)   NULL,
    completed_at     TIMESTAMP      NULL,
    version          BIGINT         NOT NULL DEFAULT 0,
    created_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_pos_drafts_business_ticket (business_id, ticket_number),
    UNIQUE KEY uq_pos_drafts_client_draft (business_id, client_draft_id),
    KEY idx_pos_drafts_branch_status (business_id, branch_id, status),
    KEY idx_pos_drafts_created_by (business_id, created_by, status),
    KEY idx_pos_drafts_updated_at (business_id, updated_at),

    CONSTRAINT fk_pd_business   FOREIGN KEY (business_id)  REFERENCES businesses(id),
    CONSTRAINT fk_pd_branch     FOREIGN KEY (branch_id)    REFERENCES branches(id),
    CONSTRAINT fk_pd_created_by FOREIGN KEY (created_by)   REFERENCES users(id),
    CONSTRAINT fk_pd_shift      FOREIGN KEY (shift_id)     REFERENCES shifts(id),
    CONSTRAINT fk_pd_sale       FOREIGN KEY (sale_id)      REFERENCES sales(id)
);

CREATE TABLE pos_draft_lines (
    id              CHAR(36)       NOT NULL PRIMARY KEY,
    draft_id        CHAR(36)       NOT NULL,
    business_id     CHAR(36)       NOT NULL,
    line_index      INT            NOT NULL,
    item_id         CHAR(36)       NOT NULL,
    item_name       VARCHAR(500)   NOT NULL,
    item_barcode    VARCHAR(128)   NULL,
    quantity        DECIMAL(14,4)  NOT NULL,
    unit_price      DECIMAL(14,4)  NOT NULL,
    discount_amount DECIMAL(14,4)  NOT NULL DEFAULT 0,
    line_total      DECIMAL(14,2)  NOT NULL,
    is_deleted      TINYINT(1)     NOT NULL DEFAULT 0,
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_pdl_draft_line (draft_id, line_index),
    KEY idx_pdl_draft (draft_id),
    KEY idx_pdl_item (business_id, item_id),

    CONSTRAINT fk_pdl_draft FOREIGN KEY (draft_id) REFERENCES pos_drafts(id) ON DELETE CASCADE,
    CONSTRAINT fk_pdl_item  FOREIGN KEY (item_id)  REFERENCES items(id)
);

CREATE TABLE pos_draft_audit_log (
    id          CHAR(36)      NOT NULL PRIMARY KEY,
    draft_id    CHAR(36)      NOT NULL,
    user_id     CHAR(36)      NOT NULL,
    action      VARCHAR(32)   NOT NULL,
    line_id     CHAR(36)      NULL,
    old_value   JSON          NULL,
    new_value   JSON          NULL,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_pdal_draft (draft_id, created_at),
    KEY idx_pdal_user (user_id, created_at),

    CONSTRAINT fk_pdal_draft FOREIGN KEY (draft_id) REFERENCES pos_drafts(id) ON DELETE CASCADE,
    CONSTRAINT fk_pdal_user  FOREIGN KEY (user_id)  REFERENCES users(id)
);

-- Permissions for POS draft carts
INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000114', 'pos.drafts.read',
   'View pending POS draft carts at a branch.'),
  ('11111111-0000-0000-0000-000000000115', 'pos.drafts.write',
   'Create and update own POS draft carts.'),
  ('11111111-0000-0000-0000-000000000116', 'pos.drafts.cancel.own',
   'Cancel own pending POS draft carts.'),
  ('11111111-0000-0000-0000-000000000117', 'pos.drafts.cancel.any',
   'Cancel any pending POS draft cart at a branch.');

-- Cashier: read, write, cancel own (+ sales.sell already granted separately)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'cashier'
  AND p.permission_key IN (
      'pos.drafts.read',
      'pos.drafts.write',
      'pos.drafts.cancel.own'
  );

-- Owner, admin, manager: all draft permissions
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name IN ('owner', 'admin', 'manager')
  AND p.permission_key IN (
      'pos.drafts.read',
      'pos.drafts.write',
      'pos.drafts.cancel.own',
      'pos.drafts.cancel.any'
  );

-- Stock manager can view branch drafts (oversight)
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.name = 'stock_manager'
  AND p.permission_key = 'pos.drafts.read';
