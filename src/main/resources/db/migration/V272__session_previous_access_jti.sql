-- Keep the prior access JWT valid after an in-place refresh so in-flight
-- requests that still carry the previous Bearer are not 401'd.
ALTER TABLE user_sessions
    ADD COLUMN previous_access_token_jti VARCHAR(36) NULL;

CREATE INDEX idx_user_sessions_previous_access_jti
    ON user_sessions (previous_access_token_jti);
