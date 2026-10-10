-- M3 — no-code automation rules for the WhatsApp inbox.
--
-- A rule = a trigger (keyword match / first inbound message) plus an ordered list of steps,
-- with an append-only run log so a merchant can see what fired. Column names/types match the
-- zelisline.ub.crm.domain.* entities so spring.jpa.hibernate.ddl-auto=validate passes.
-- Dialect: MySQL (MariaDB desktop must also accept it). Add-only.
-- See docs/scopes/whatsapp-crm/SCOPE.md §6.3, §9 (M3).

CREATE TABLE crm_automation (
  id                CHAR(36)     PRIMARY KEY,
  business_id       CHAR(36)     NOT NULL,
  name              VARCHAR(160) NOT NULL,
  trigger_type      VARCHAR(40)  NOT NULL,
  trigger_config    MEDIUMTEXT   NULL,
  active            BOOLEAN      NOT NULL DEFAULT FALSE,
  execution_count   INT          NOT NULL DEFAULT 0,
  last_executed_at  TIMESTAMP    NULL,
  created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_crm_automation_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  KEY idx_crm_automation_business (business_id, created_at),
  KEY idx_crm_automation_trigger (business_id, trigger_type, active)
);

CREATE TABLE crm_automation_step (
  id             CHAR(36)    PRIMARY KEY,
  automation_id  CHAR(36)    NOT NULL,
  position       INT         NOT NULL,
  step_type      VARCHAR(40) NOT NULL,
  step_config    MEDIUMTEXT  NULL,
  created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_crm_automation_step_automation (automation_id, position)
);

CREATE TABLE crm_automation_run (
  id               CHAR(36)     PRIMARY KEY,
  business_id      CHAR(36)     NOT NULL,
  automation_id    CHAR(36)     NOT NULL,
  conversation_id  CHAR(36)     NULL,
  contact_id       CHAR(36)     NULL,
  trigger_event    VARCHAR(60)  NOT NULL,
  status           VARCHAR(16)  NOT NULL,
  steps_json       MEDIUMTEXT   NULL,
  error_message    VARCHAR(512) NULL,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_crm_automation_run_business (business_id, created_at),
  KEY idx_crm_automation_run_automation (automation_id, created_at)
);

-- Automation permissions for tenant roles (owner/admin/manager), mirroring V335's pattern.
INSERT IGNORE INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000423', 'crm.automation.manage', 'Create and manage WhatsApp inbox automation rules.');

INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'crm.automation.manage'
  AND r.role_key IN ('owner', 'admin', 'manager');
