-- ADR 0030: a room type is the rate card for a category of room (Standard Double, Executive…).
-- Business- and branch-scoped. A room may belong to a type and inherit its rate card; a per-room
-- nightly_rate (V401) overrides the type's rate for that one room. The weekend rate is optional —
-- null means a weekend night costs the same as a weekday night. Expand-only; indexed by business.
CREATE TABLE room_types (
    id                   VARCHAR(36)  NOT NULL,
    business_id          VARCHAR(36)  NOT NULL,
    branch_id            VARCHAR(36)  NOT NULL,
    name                 VARCHAR(80)  NOT NULL,
    default_nightly_rate DECIMAL(14,2) NOT NULL,
    weekend_nightly_rate DECIMAL(14,2) NULL,
    capacity             INT          NULL,
    bed_config           VARCHAR(120) NULL,
    amenities            VARCHAR(500) NULL,
    sort_order           INT          NOT NULL DEFAULT 0,
    created_at           DATETIME(6)  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_room_types_name UNIQUE (business_id, branch_id, name)
);

CREATE INDEX ix_room_types_business_branch ON room_types (business_id, branch_id);
