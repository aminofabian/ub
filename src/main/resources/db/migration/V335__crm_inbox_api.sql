-- M2 — CRM inbox API support: internal notes + quick replies, and inbox permissions.
--
-- Column names/types match the zelisline.ub.crm.* entities so
-- spring.jpa.hibernate.ddl-auto=validate passes. Dialect: MySQL.
-- See docs/scopes/whatsapp-crm/SCOPE.md §6.3 and §8.

CREATE TABLE crm_note (
  id              CHAR(36)   PRIMARY KEY,
  business_id     CHAR(36)   NOT NULL,
  conversation_id CHAR(36)   NOT NULL,
  author_user_id  CHAR(36)   NOT NULL,
  body            MEDIUMTEXT NOT NULL,
  created_at      TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_crm_note_conversation (conversation_id, created_at)
);

CREATE TABLE crm_quick_reply (
  id           CHAR(36)   PRIMARY KEY,
  business_id  CHAR(36)   NOT NULL,
  shortcut     VARCHAR(64) NOT NULL,
  body         MEDIUMTEXT NOT NULL,
  created_at   TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_crm_quick_reply_biz_shortcut (business_id, shortcut)
);

-- Inbox permissions for tenant roles (owner/admin/manager), mirroring V121's pattern.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000420', 'crm.inbox.read',   'View WhatsApp inbox conversations and messages.'),
  ('11111111-0000-0000-0000-000000000421', 'crm.inbox.send',   'Send replies from the WhatsApp inbox.'),
  ('11111111-0000-0000-0000-000000000422', 'crm.inbox.manage', 'Assign conversations, add notes, and manage quick replies.');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN ('crm.inbox.read', 'crm.inbox.send', 'crm.inbox.manage')
  AND r.role_key IN ('owner', 'admin', 'manager');
