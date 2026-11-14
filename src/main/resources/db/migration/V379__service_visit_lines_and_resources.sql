-- A customer visit is not one service, one person or one chair (ADR 0016).
--
-- 1. Each service on a visit now carries its own place and progress: which chair, bed or room it
--    used, and when it started and finished. Who did it was already on the line.
-- 2. Chairs become named resources. branches.service_station_count could only say "three chairs";
--    a spa needs "Bed 2" and "Steam room", each with a type, a cleaning time and a way to take it
--    out of use. This needs a table; nothing existing can hold it (scope §16).
-- 3. A service may say which kind of resource it needs.
--
-- All column additions are nullable, so held carts and tickets already open are untouched.
-- The two indexes serve the "is this person / this chair busy right now" checks, which are
-- locking reads and must not scan the table.

CREATE TABLE IF NOT EXISTS service_resources (
  id               CHAR(36)     NOT NULL PRIMARY KEY,
  business_id      CHAR(36)     NOT NULL,
  branch_id        CHAR(36)     NOT NULL,
  name             VARCHAR(80)  NOT NULL,
  -- CHAIR, STATION, BED, ROOM, EQUIPMENT ...: free text chosen from a short list in the app.
  resource_type    VARCHAR(24)  NOT NULL,
  -- The old chair number (1..N) for resources made from branches.service_station_count.
  station_no       INT          NULL,
  active           TINYINT(1)   NOT NULL DEFAULT 1,
  -- Minutes it stays unavailable after a service, for cleaning or preparation.
  cleanup_minutes  INT          NOT NULL DEFAULT 0,
  -- Out of use (maintenance) until this moment; NULL means in use.
  blocked_until    TIMESTAMP    NULL,
  blocked_reason   VARCHAR(191) NULL,
  sort_order       INT          NOT NULL DEFAULT 0,
  version          BIGINT       NOT NULL DEFAULT 0,
  created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_service_resources_name (business_id, branch_id, name),
  KEY idx_service_resources_branch (business_id, branch_id, active, sort_order),
  CONSTRAINT fk_service_resources_business FOREIGN KEY (business_id) REFERENCES businesses (id),
  CONSTRAINT fk_service_resources_branch FOREIGN KEY (branch_id) REFERENCES branches (id)
);

ALTER TABLE pos_draft_lines
  ADD COLUMN resource_id CHAR(36) NULL,
  -- WAITING, IN_SERVICE or DONE; NULL on held-cart lines and on tickets from before this change.
  ADD COLUMN line_stage VARCHAR(16) NULL,
  ADD COLUMN started_at TIMESTAMP NULL,
  ADD COLUMN finished_at TIMESTAMP NULL,
  ADD KEY idx_pos_draft_lines_resource (resource_id, line_stage),
  ADD KEY idx_pos_draft_lines_performer (performer_staff_profile_id, line_stage);

-- The kind of resource a service needs (matches service_resources.resource_type); NULL: any or none.
ALTER TABLE services
  ADD COLUMN resource_type VARCHAR(24) NULL;
