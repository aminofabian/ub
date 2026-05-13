-- Phase 9 Slice 4: Offline sync conflict inbox
CREATE TABLE sync_conflicts (
    id              CHAR(36)       PRIMARY KEY,
    business_id     CHAR(36)       NOT NULL,
    entity_type     VARCHAR(64)    NOT NULL,  -- e.g. 'item', 'selling_price', 'category'
    entity_id       CHAR(36)       NOT NULL,
    local_version   TIMESTAMP      NOT NULL,  -- updated_at from client
    server_version  TIMESTAMP      NOT NULL,  -- updated_at from server at detection
    resolution      VARCHAR(32)    NOT NULL DEFAULT 'pending', -- pending | local_wins | server_wins | merged
    local_snapshot  JSON           NULL,       -- the client's version (what they thought they were editing)
    server_snapshot JSON           NULL,       -- the server's current version
    created_by      CHAR(36)       NULL,
    created_at      TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at     TIMESTAMP      NULL,
    resolved_by     CHAR(36)       NULL,
    notes           VARCHAR(2000)  NULL,
    CONSTRAINT fk_sync_conflicts_business FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE CASCADE
);

CREATE INDEX idx_sync_conflicts_lookup ON sync_conflicts (business_id, entity_type, entity_id, resolution);
