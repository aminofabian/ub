-- ADR 0030 Phase 2 (hospitality maintenance tickets): a standalone maintenance issue on a room that
-- OUTLIVES the housekeeping flag. The room's `housekeeping_status = MAINTENANCE` and its free-text
-- `housekeeping_note` are wiped the moment the room is marked CLEAN/INSPECTED (ServiceResourceService),
-- so today a guest house keeps no memory of what broke, who fixed it, or that it has broken before.
-- A persistent issue row gives that memory: a description, an open→in-progress→done lifecycle, an
-- optional photo (stored URL, uploaded via the existing /api/v1/media path), an optional assignee,
-- and open/close timestamps. Business- and branch-scoped like every other tenant table.
--
-- Small table, append-only in practice; a plain CREATE with the two access-path indexes. No backfill:
-- rooms start with no issues.
CREATE TABLE room_maintenance_issues (
    id                        VARCHAR(36)  NOT NULL,
    business_id               VARCHAR(36)  NOT NULL,
    branch_id                 VARCHAR(36)  NOT NULL,
    room_resource_id          VARCHAR(36)  NOT NULL,
    description               VARCHAR(500) NOT NULL,
    -- OPEN, IN_PROGRESS, DONE (see RoomMaintenanceIssue).
    status                    VARCHAR(16)  NOT NULL,
    -- The guest's basin, a broken lock: a photo stored elsewhere, referenced by URL. Nullable.
    photo_url                 VARCHAR(1024) NULL,
    -- Who is fixing it, reusing the staff_profiles id space; nullable.
    assigned_staff_profile_id VARCHAR(36)  NULL,
    opened_by                 VARCHAR(36)  NULL,
    opened_at                 DATETIME(6)  NOT NULL,
    closed_at                 DATETIME(6)  NULL,
    version                   BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

-- The front desk lists a branch's open issues; a room shows its own history. Both paths lead with
-- business_id so one tenant never sees another's.
CREATE INDEX ix_room_maint_business_branch_status
    ON room_maintenance_issues (business_id, branch_id, status);
CREATE INDEX ix_room_maint_business_room
    ON room_maintenance_issues (business_id, room_resource_id);
