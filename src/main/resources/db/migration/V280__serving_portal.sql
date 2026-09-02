-- Customer Serving portal: desk roles on super_admins + tickets with assignment.

ALTER TABLE super_admins
  ADD COLUMN desk_role VARCHAR(16) NOT NULL DEFAULT 'owner';

UPDATE super_admins SET desk_role = 'owner' WHERE desk_role IS NULL OR desk_role = '';

CREATE TABLE serving_ticket_counters (
  id          VARCHAR(16) PRIMARY KEY,
  next_number INT NOT NULL
);

INSERT INTO serving_ticket_counters (id, next_number) VALUES ('default', 1001);

CREATE TABLE serving_tickets (
  id                  CHAR(36) PRIMARY KEY,
  ticket_number       INT NOT NULL,
  type                VARCHAR(16) NOT NULL,
  status              VARCHAR(16) NOT NULL,
  priority            VARCHAR(16) NOT NULL,
  category            VARCHAR(32) NOT NULL,
  subject             VARCHAR(191) NOT NULL,
  business_id         CHAR(36) NULL,
  requester_user_id   CHAR(36) NULL,
  requester_name      VARCHAR(191) NULL,
  requester_email     VARCHAR(191) NULL,
  requester_phone     VARCHAR(32) NULL,
  shopper_guest_id    VARCHAR(64) NULL,
  shopper_name        VARCHAR(120) NULL,
  shopper_phone       VARCHAR(32) NULL,
  shopper_user_id     CHAR(36) NULL,
  order_id            CHAR(36) NULL,
  assigned_to         CHAR(36) NULL,
  assigned_at         TIMESTAMP NULL,
  conversation_id     CHAR(36) NULL,
  contact_message_id  CHAR(36) NULL,
  thread_from         TIMESTAMP NULL,
  created_by          CHAR(36) NULL,
  created_by_kind     VARCHAR(16) NOT NULL,
  created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  resolved_at         TIMESTAMP NULL,
  closed_at           TIMESTAMP NULL,
  UNIQUE KEY uq_serving_tickets_number (ticket_number),
  INDEX idx_serving_tickets_status (status, updated_at),
  INDEX idx_serving_tickets_assignee (assigned_to, status),
  INDEX idx_serving_tickets_conversation (conversation_id),
  INDEX idx_serving_tickets_contact (contact_message_id),
  INDEX idx_serving_tickets_business (business_id, status)
);

CREATE TABLE serving_ticket_notes (
  id          CHAR(36) PRIMARY KEY,
  ticket_id   CHAR(36) NOT NULL,
  author_id   CHAR(36) NOT NULL,
  author_name VARCHAR(191) NULL,
  body        TEXT NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_serving_notes_ticket FOREIGN KEY (ticket_id) REFERENCES serving_tickets(id) ON DELETE CASCADE,
  INDEX idx_serving_notes_ticket (ticket_id, created_at)
);

CREATE TABLE serving_ticket_events (
  id          CHAR(36) PRIMARY KEY,
  ticket_id   CHAR(36) NOT NULL,
  actor_id    CHAR(36) NULL,
  actor_name  VARCHAR(191) NULL,
  kind        VARCHAR(32) NOT NULL,
  payload     VARCHAR(500) NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_serving_events_ticket FOREIGN KEY (ticket_id) REFERENCES serving_tickets(id) ON DELETE CASCADE,
  INDEX idx_serving_events_ticket (ticket_id, created_at)
);
