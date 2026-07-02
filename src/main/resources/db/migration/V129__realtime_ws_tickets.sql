-- Ephemeral single-use WebSocket handshake tickets (60s TTL).
-- Shared across API replicas when Redis is unavailable.

CREATE TABLE realtime_ws_tickets (
    ticket_hash      VARCHAR(64)   NOT NULL PRIMARY KEY,
    user_id          CHAR(36)      NOT NULL,
    business_id      CHAR(36)      NOT NULL,
    branch_id        CHAR(36)      NULL,
    allowed_channels VARCHAR(512)  NOT NULL,
    issued_at        TIMESTAMP(3)  NOT NULL,
    expires_at       TIMESTAMP(3)  NOT NULL,

    KEY idx_rwst_expires_at (expires_at)
);
