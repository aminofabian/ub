-- M2 — contact tags for the WhatsApp inbox.
-- `crm_tag` is the per-business tag vocabulary (name + colour). Assignments are stored on
-- `crm_contact.tags_json` (a JSON array of tag names), so no join table is needed.
-- Column names/types match zelisline.ub.crm.domain.CrmTag so ddl-auto=validate passes.
-- See docs/scopes/whatsapp-crm/SCOPE.md §6.3.

CREATE TABLE crm_tag (
  id           CHAR(36)    PRIMARY KEY,
  business_id  CHAR(36)    NOT NULL,
  name         VARCHAR(64) NOT NULL,
  color        VARCHAR(16) NULL,
  created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_crm_tag_business_name (business_id, name)
);
