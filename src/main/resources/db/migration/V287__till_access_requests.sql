-- When PIN login is blocked because a branch already has trusted tills and
-- this computer is not one of them, record a pending access request and
-- notify owners/admins with a one-tap register link.

CREATE TABLE till_access_requests (
    id                      CHAR(36)      NOT NULL PRIMARY KEY,
    business_id             CHAR(36)      NOT NULL,
    branch_id               CHAR(36)      NOT NULL,
    device_key              VARCHAR(64)   NOT NULL,
    requested_by_user_id    CHAR(36)      NOT NULL,
    requested_by_name       VARCHAR(160)  NOT NULL,
    requested_by_email      VARCHAR(191)  NOT NULL,
    suggested_label         VARCHAR(80)   NOT NULL,
    user_agent              VARCHAR(240)  NULL,
    status                  VARCHAR(16)   NOT NULL,
    last_seen_at            TIMESTAMP     NOT NULL,
    notified_at             TIMESTAMP     NULL,
    notify_count            INT           NOT NULL DEFAULT 0,
    resolved_by             CHAR(36)      NULL,
    resolved_at             TIMESTAMP     NULL,
    created_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_till_access_device (business_id, branch_id, device_key),
    KEY idx_till_access_business_status (business_id, status),
    KEY idx_till_access_pending_seen (status, last_seen_at),

    CONSTRAINT fk_till_access_business FOREIGN KEY (business_id) REFERENCES businesses(id),
    CONSTRAINT fk_till_access_branch   FOREIGN KEY (branch_id)   REFERENCES branches(id),
    CONSTRAINT fk_till_access_user     FOREIGN KEY (requested_by_user_id) REFERENCES users(id),
    CONSTRAINT fk_till_access_resolver FOREIGN KEY (resolved_by) REFERENCES users(id)
);

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000052', NULL, 'till.access_requested', 'en', 1,
   '{{cashierName}} is waiting at {{branchName}}',
   '{{cashierName}} tried to unlock the till on a computer that is not registered. One tap trusts this counter so they can sell. {{publicUrl}}',
   '{{publicUrl}}', 'TRANSACTIONAL', 'cash_drawer',
   '["IN_APP","EMAIL","WEB_PUSH","SMS","WHATSAPP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  default_channels = VALUES(default_channels),
  active = TRUE;
