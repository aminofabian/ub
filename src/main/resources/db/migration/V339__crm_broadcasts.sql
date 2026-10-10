-- M4 — WhatsApp broadcast (fan-out) for the CRM.
--
-- A broadcast targets a set of contacts (all, or by tag). Free-form mode only reaches contacts
-- with an open 24h customer-service window; template mode can reach cold contacts with an
-- approved template. Fan-out reuses crm_outbound (one row per recipient) so the existing drain
-- and delivery-status path apply; crm_broadcast_recipient is the per-recipient ledger.
-- Column names/types match the zelisline.ub.crm.domain.* entities (Hibernate validate). MySQL.
-- Add-only. See docs/scopes/whatsapp-crm/M4-PLAN.md.

ALTER TABLE crm_outbound ADD COLUMN broadcast_recipient_id CHAR(36) NULL;
ALTER TABLE crm_outbound ADD KEY idx_crm_outbound_broadcast_recipient (broadcast_recipient_id);

CREATE TABLE crm_broadcast (
  id                 CHAR(36)     PRIMARY KEY,
  business_id        CHAR(36)     NOT NULL,
  name               VARCHAR(160) NOT NULL,
  mode               VARCHAR(16)  NOT NULL,
  body               MEDIUMTEXT   NULL,
  template_name      VARCHAR(128) NULL,
  template_language  VARCHAR(16)  NULL,
  audience_json      MEDIUMTEXT   NULL,
  status             VARCHAR(16)  NOT NULL DEFAULT 'draft',
  total_count        INT          NOT NULL DEFAULT 0,
  created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_broadcast_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_crm_broadcast_business (business_id, created_at)
);

CREATE TABLE crm_broadcast_recipient (
  id             CHAR(36)     PRIMARY KEY,
  broadcast_id   CHAR(36)     NOT NULL,
  business_id    CHAR(36)     NOT NULL,
  contact_id     CHAR(36)     NULL,
  phone_e164     VARCHAR(20)  NOT NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'pending',
  wa_message_id  VARCHAR(128) NULL,
  error_message  VARCHAR(512) NULL,
  sent_at        TIMESTAMP    NULL,
  delivered_at   TIMESTAMP    NULL,
  read_at        TIMESTAMP    NULL,
  created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_crm_bcast_recip_broadcast_phone (broadcast_id, phone_e164),
  KEY idx_crm_bcast_recip_broadcast (broadcast_id, status),
  KEY idx_crm_bcast_recip_wamid (wa_message_id)
);

-- Broadcast permissions for tenant roles (owner/admin/manager), mirroring V335/V338.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000424', 'crm.broadcast.send', 'Send WhatsApp broadcasts to customers.');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'crm.broadcast.send'
  AND r.role_key IN ('owner', 'admin', 'manager');
