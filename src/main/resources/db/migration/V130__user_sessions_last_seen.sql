-- Track last activity for idle session timeout (default 12h via app.auth.idle-timeout-hours).

ALTER TABLE user_sessions
  ADD COLUMN last_seen_at TIMESTAMP NULL;

UPDATE user_sessions
   SET last_seen_at = issued_at
 WHERE last_seen_at IS NULL;

ALTER TABLE user_sessions
  MODIFY last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
