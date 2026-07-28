-- SokoMind Guide request audit log (tokens, latency, thumbs).

CREATE TABLE ai_request_log (
    id              CHAR(36)     NOT NULL PRIMARY KEY,
    business_id     CHAR(36)     NOT NULL,
    user_id         CHAR(36)     NULL,
    skill           VARCHAR(64)  NOT NULL,
    surface         VARCHAR(128) NULL,
    route_path      VARCHAR(512) NULL,
    provider        VARCHAR(32)  NULL,
    model           VARCHAR(128) NULL,
    prompt_tokens   INT          NULL,
    completion_tokens INT        NULL,
    latency_ms      INT          NULL,
    success         TINYINT(1)   NOT NULL DEFAULT 1,
    error_message   VARCHAR(512) NULL,
    feedback        VARCHAR(16)  NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_ai_request_log_business_created (business_id, created_at),
    INDEX idx_ai_request_log_user_created (user_id, created_at)
);
