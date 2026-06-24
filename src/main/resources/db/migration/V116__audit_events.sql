-- Unified, append-only audit event log for POS/e-commerce operations.
--
-- Design notes:
-- * No updated_at / deleted_at columns: audit rows are immutable.
-- * old_state, new_state, diff, metadata are JSON for flexibility across event types.
-- * Indexes are business-scoped and time-descending to support the primary query patterns:
--   "show me everything that happened in my business today" and "show me the history of entity X".

CREATE TABLE audit_events (
  id                 CHAR(36)      NOT NULL PRIMARY KEY,
  business_id        CHAR(36)      NOT NULL,
  branch_id          CHAR(36)      NULL,
  category           VARCHAR(40)   NOT NULL,
  event_type         VARCHAR(80)   NOT NULL,
  severity           VARCHAR(20)   NOT NULL,
  actor_id           CHAR(36)      NULL,
  actor_type         VARCHAR(20)   NOT NULL,
  actor_name         VARCHAR(255)  NULL,
  target_type        VARCHAR(60)   NULL,
  target_id          CHAR(36)      NULL,
  target_label       VARCHAR(255)  NULL,
  session_id         CHAR(36)      NULL,
  correlation_id     CHAR(36)      NULL,
  ip_address         VARCHAR(45)   NULL,
  user_agent         VARCHAR(512)  NULL,
  source             VARCHAR(40)   NULL,
  terminal_id        VARCHAR(80)   NULL,
  shift_id           CHAR(36)      NULL,
  old_state          JSON          NULL,
  new_state          JSON          NULL,
  diff               JSON          NULL,
  reason             VARCHAR(500)  NULL,
  metadata           JSON          NULL,
  created_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  KEY idx_audit_business_time     (business_id, created_at DESC),
  KEY idx_audit_category_type     (business_id, category, event_type, created_at DESC),
  KEY idx_audit_actor             (business_id, actor_id, created_at DESC),
  KEY idx_audit_target            (business_id, target_type, target_id, created_at DESC),
  KEY idx_audit_correlation       (correlation_id),
  KEY idx_audit_shift             (shift_id, created_at DESC)
);
