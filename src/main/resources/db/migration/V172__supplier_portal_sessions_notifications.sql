-- Supplier portal sessions (revocable access JTIs) + in-app notifications / prefs.

CREATE TABLE supplier_user_sessions (
  id                       CHAR(36)     NOT NULL PRIMARY KEY,
  supplier_user_id         CHAR(36)     NOT NULL,
  marketplace_supplier_id  CHAR(36)     NOT NULL,
  access_token_jti         CHAR(36)     NOT NULL,
  user_agent               VARCHAR(500) NULL,
  ip                       VARCHAR(45)  NULL,
  issued_at                TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at               TIMESTAMP(3) NOT NULL,
  revoked_at               TIMESTAMP(3) NULL,
  last_seen_at             TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_supplier_user_sessions_user
    FOREIGN KEY (supplier_user_id) REFERENCES supplier_users (id),
  CONSTRAINT fk_supplier_user_sessions_supplier
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id),
  UNIQUE KEY uq_supplier_user_sessions_jti (access_token_jti)
);

CREATE INDEX idx_supplier_user_sessions_user
  ON supplier_user_sessions (supplier_user_id, revoked_at, issued_at DESC);

CREATE TABLE supplier_portal_notifications (
  id                       CHAR(36)     NOT NULL PRIMARY KEY,
  marketplace_supplier_id  CHAR(36)     NOT NULL,
  supplier_user_id         CHAR(36)     NULL,
  type                     VARCHAR(64)  NOT NULL,
  title                    VARCHAR(255) NOT NULL,
  body                     TEXT         NOT NULL,
  action_url               VARCHAR(512) NULL,
  read_at                  TIMESTAMP(3) NULL,
  created_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_sp_notifications_supplier
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id),
  CONSTRAINT fk_sp_notifications_user
    FOREIGN KEY (supplier_user_id) REFERENCES supplier_users (id)
);

CREATE INDEX idx_sp_notifications_inbox
  ON supplier_portal_notifications (marketplace_supplier_id, created_at DESC);

CREATE TABLE supplier_portal_notification_prefs (
  supplier_user_id         CHAR(36)     NOT NULL PRIMARY KEY,
  marketplace_supplier_id  CHAR(36)     NOT NULL,
  notify_po_in_app         BOOLEAN      NOT NULL DEFAULT TRUE,
  notify_po_sms            BOOLEAN      NOT NULL DEFAULT TRUE,
  notify_payment_in_app    BOOLEAN      NOT NULL DEFAULT TRUE,
  notify_payment_sms       BOOLEAN      NOT NULL DEFAULT TRUE,
  notify_delivery_in_app   BOOLEAN      NOT NULL DEFAULT TRUE,
  updated_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_sp_notif_prefs_user
    FOREIGN KEY (supplier_user_id) REFERENCES supplier_users (id),
  CONSTRAINT fk_sp_notif_prefs_supplier
    FOREIGN KEY (marketplace_supplier_id) REFERENCES marketplace_suppliers (id)
);
