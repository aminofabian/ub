-- V199: Shared order pad — cashiers / stock managers list items to order;
-- admins mark lines as already ordered.

CREATE TABLE order_pad_items (
    id           CHAR(36)       NOT NULL PRIMARY KEY,
    business_id  CHAR(36)       NOT NULL,
    branch_id    CHAR(36)       NOT NULL,
    item_id      CHAR(36)       NULL,
    item_name    VARCHAR(500)   NOT NULL,
    quantity     DECIMAL(14,4)  NULL,
    note         TEXT           NULL,
    ordered      TINYINT(1)     NOT NULL DEFAULT 0,
    ordered_by   CHAR(36)       NULL,
    ordered_at   TIMESTAMP(6)   NULL,
    created_by   CHAR(36)       NOT NULL,
    version      BIGINT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   TIMESTAMP(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    KEY idx_order_pad_branch_ordered (business_id, branch_id, ordered, created_at),
    KEY idx_order_pad_created_by (business_id, created_by, created_at),
    KEY idx_order_pad_item (business_id, item_id),

    CONSTRAINT fk_opi_business  FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_opi_branch    FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_opi_item      FOREIGN KEY (item_id)     REFERENCES items(id),
    CONSTRAINT fk_opi_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT fk_opi_ordered_by FOREIGN KEY (ordered_by) REFERENCES users(id)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000700', 'order_pad.write',
   'Add and remove items on the shared to-order pad.'),
  ('11111111-0000-0000-0000-000000000701', 'order_pad.read',
   'View the shared to-order pad list.'),
  ('11111111-0000-0000-0000-000000000702', 'order_pad.manage',
   'Mark order-pad lines as ordered and manage any line.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN ('order_pad.write', 'order_pad.read')
  AND r.role_key IN ('owner', 'admin', 'manager', 'cashier', 'stock_manager')
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'order_pad.manage'
  AND r.role_key IN ('owner', 'admin', 'manager')
  AND NOT EXISTS (
    SELECT 1 FROM role_permissions rp
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
  );
