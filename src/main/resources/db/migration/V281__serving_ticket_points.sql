-- Numbered worklist on a serving ticket. Palmart staff (and SokoMind) break a
-- rambling thread into points 1, 2, 3… the shop sees the same list and ticks them.

CREATE TABLE serving_ticket_points (
  id                   CHAR(36) PRIMARY KEY,
  ticket_id            CHAR(36) NOT NULL,
  seq                  INT NOT NULL,
  title                VARCHAR(191) NOT NULL,
  detail               TEXT NULL,
  status               VARCHAR(16) NOT NULL,
  source               VARCHAR(16) NOT NULL,
  completed_at         TIMESTAMP NULL,
  completed_by         CHAR(36) NULL,
  completed_by_name    VARCHAR(191) NULL,
  completed_by_kind    VARCHAR(16) NULL,
  created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_serving_points_ticket FOREIGN KEY (ticket_id) REFERENCES serving_tickets(id) ON DELETE CASCADE,
  UNIQUE KEY uq_serving_points_ticket_seq (ticket_id, seq),
  INDEX idx_serving_points_ticket (ticket_id, seq)
);
