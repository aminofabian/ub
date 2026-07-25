-- Talk to Us: platform + tenant contact inbox.

CREATE TABLE contact_messages (
  id            CHAR(36)     PRIMARY KEY,
  scope         VARCHAR(16)  NOT NULL,
  business_id   CHAR(36)     NULL,
  name          VARCHAR(120) NOT NULL,
  email         VARCHAR(255) NOT NULL,
  phone         VARCHAR(32)  NULL,
  body          VARCHAR(4000) NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  read_at       TIMESTAMP    NULL,
  source_path   VARCHAR(512) NULL,
  user_agent    VARCHAR(512) NULL,
  created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_contact_messages_scope CHECK (scope IN ('PLATFORM', 'TENANT')),
  CONSTRAINT chk_contact_messages_status CHECK (status IN ('UNREAD', 'READ')),
  CONSTRAINT fk_contact_messages_business
    FOREIGN KEY (business_id) REFERENCES businesses (id),
  INDEX idx_contact_messages_scope_biz_created (scope, business_id, created_at),
  INDEX idx_contact_messages_scope_status (scope, status)
);

CREATE TABLE contact_message_replies (
  id                  CHAR(36)     PRIMARY KEY,
  contact_message_id  CHAR(36)     NOT NULL,
  channel             VARCHAR(16)  NOT NULL,
  body                VARCHAR(4000) NOT NULL,
  outcome             VARCHAR(32)  NOT NULL,
  detail              VARCHAR(1000) NULL,
  sent_by_user_id     CHAR(36)     NULL,
  created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_contact_message_replies_channel
    CHECK (channel IN ('EMAIL', 'WHATSAPP', 'SMS')),
  CONSTRAINT fk_contact_message_replies_message
    FOREIGN KEY (contact_message_id) REFERENCES contact_messages (id),
  INDEX idx_contact_message_replies_message_created (contact_message_id, created_at)
);

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000093', 'messages.read',
   'View Talk to Us contact messages for the business.'),
  ('11111111-0000-0000-0000-000000000094', 'messages.reply',
   'Reply to Talk to Us contact messages via email, WhatsApp, or SMS.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key IN ('messages.read', 'messages.reply')
  AND r.role_key IN ('owner', 'admin')
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );
