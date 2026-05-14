-- V75: Add sequential session_number per business to stock_take_sessions.
-- Existing rows keep 0 (legacy). New sessions are assigned MAX+1 by the service.
ALTER TABLE stock_take_sessions
  ADD COLUMN session_number INT NOT NULL DEFAULT 0;
